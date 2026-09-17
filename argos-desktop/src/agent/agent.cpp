#include "agent/agent.h"

#include <windows.h>

#include <winhttp.h>

#include <chrono>
#include <format>
#include <thread>

#include "bridge/ide_bridge.h"
#include "core/app.h"
#include "core/config.h"
#include "core/log.h"
#include "platform/win_util.h"
#include "tools/tools.h"

namespace argos::agent {
namespace {

constexpr int kMaxRounds = 12;            // tool-call iterations per turn
constexpr size_t kToolOutputCap = 6000;   // chars of tool output fed back
constexpr size_t kReplyCap = 4000;        // phone replies stay short

const char* kSystemPrompt = R"(You are Argos, a small robot companion living on the user's Windows desktop. You can act on the machine directly through tools.

Environment: Windows 11. File tools accept absolute paths or paths relative to the IDE workspace / current directory.

Rules:
- Prefer read_file / search_files / list_files to inspect before changing anything. Read a file before editing it.
- For edits prefer apply_diff (surgical SEARCH/REPLACE) over write_file rewrites.
- delete_file requires confirmed=true — only set it when the user clearly asked for deletion.
- execute_command runs in a terminal (IDE terminal when an editor is connected, else cmd.exe). Keep commands non-interactive.
- When a tool call fails, adapt: check the error, try a different path or approach, don't blindly retry.
- Be concise in final answers — you are a desktop companion, not a document.
- If a task is impossible (missing app, no permission), say so plainly instead of pretending.
- You may delegate work to configured sub-agents (ask_* tools) — they are specialist AI models that return text only and cannot run tools themselves. Give them complete, self-contained tasks.)";

// Tool-name-safe suffix: letters, digits, _ and - only.
std::string sanitize_tool_name(const std::string& name) {
    std::string out;
    for (char c : name) {
        if (isalnum((unsigned char)c) || c == '_' || c == '-')
            out += (char)tolower((unsigned char)c);
        else if (c == ' ' || c == '.')
            out += '_';
    }
    if (out.empty()) out = "agent";
    return out;
}

// One extra tool per configured Bitdeer sub-agent: ask_<name>(task, context?).
nlohmann::json subagent_tools() {
    nlohmann::json tools = nlohmann::json::array();
    if (config().bitdeer.api_key.empty()) return tools;
    for (const auto& sa : config().bitdeer.agents) {
        if (sa.name.empty() || sa.model.empty()) continue;
        nlohmann::json params = {
            {"type", "object"},
            {"properties",
             {{"task",
               {{"type", "string"},
                {"description", "The complete task or question for the sub-agent"}}},
              {"context",
               {{"type", "string"},
                {"description", "Optional extra context, e.g. file contents or constraints"}}}}},
            {"required", nlohmann::json::array({"task"})}};
        nlohmann::json tool = {
            {"type", "function"},
            {"function",
             {{"name", "ask_" + sanitize_tool_name(sa.name)},
              {"description",
               std::format("Delegate to the '{}' sub-agent ({}). {} Give it "
                           "a complete, self-contained task; it returns text "
                           "only and cannot run tools.",
                           sa.name, sa.model,
                           sa.prompt.empty() ? "General assistant." : sa.prompt)},
              {"parameters", params}}}};
        tools.push_back(std::move(tool));
    }
    return tools;
}

std::string truncate(const std::string& s, size_t cap) {
    if (s.size() <= cap) return s;
    return s.substr(0, cap) + std::format("\n… [{} bytes truncated]", s.size() - cap);
}

// value() throws on present-but-null; models do send nulls sometimes.
std::string str_arg(const nlohmann::json& args, const char* key) {
    auto it = args.find(key);
    return (it != args.end() && it->is_string()) ? it->get<std::string>() : "";
}

// POST a JSON body over HTTPS (or HTTP) and return the decoded body.
std::optional<std::string> post_json(const std::string& base_url,
                                     const std::string& path,
                                     const std::string& bearer,
                                     const nlohmann::json& body,
                                     int timeout_seconds,
                                     std::string* err) {
    URL_COMPONENTSW uc{};
    uc.dwStructSize = sizeof(uc);
    wchar_t host[256]{}, urlpath[1024]{}, scheme[16]{};
    uc.lpszHostName = host;  uc.dwHostNameLength = 256;
    uc.lpszUrlPath = urlpath; uc.dwUrlPathLength = 1024;
    uc.lpszScheme = scheme;  uc.dwSchemeLength = 16;

    std::wstring wbase = win::to_wide(base_url);
    if (!WinHttpCrackUrl(wbase.c_str(), 0, 0, &uc)) {
        if (err) *err = "bad base_url " + base_url;
        return std::nullopt;
    }
    const bool https = uc.nScheme == INTERNET_SCHEME_HTTPS;
    std::wstring full_path = std::wstring(urlpath, uc.dwUrlPathLength) +
                             win::to_wide(path);

    HINTERNET session =
        WinHttpOpen(L"ArgosDesktop/0.1", WINHTTP_ACCESS_TYPE_NO_PROXY,
                    WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) {
        if (err) *err = "WinHttpOpen failed";
        return std::nullopt;
    }
    HINTERNET connection = nullptr, req = nullptr;
    struct Guard {
        HINTERNET& a; HINTERNET& b; HINTERNET& c;
        ~Guard() {
            if (c) WinHttpCloseHandle(c);
            if (b) WinHttpCloseHandle(b);
            if (a) WinHttpCloseHandle(a);
        }
    } guard{session, connection, req};

    DWORD t = (DWORD)timeout_seconds * 1000;
    WinHttpSetTimeouts(session, t, t, t, t);
    connection = WinHttpConnect(session, host, uc.nPort, 0);
    if (!connection) {
        if (err) *err = "connect failed (host unreachable?)";
        return std::nullopt;
    }
    req = WinHttpOpenRequest(connection, L"POST", full_path.c_str(), nullptr,
                             WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                             https ? WINHTTP_FLAG_SECURE : 0);
    if (!req) {
        if (err) *err = "open request failed";
        return std::nullopt;
    }

    std::wstring headers =
        L"Content-Type: application/json\r\nAuthorization: Bearer " +
        win::to_wide(bearer) + L"\r\n";
    std::string payload = body.dump();
    if (!WinHttpSendRequest(req, headers.c_str(), (DWORD)headers.size(),
                          payload.data(), (DWORD)payload.size(),
                          (DWORD)payload.size(), 0) ||
        !WinHttpReceiveResponse(req, nullptr)) {
        if (err) *err = "send/receive failed";
        return std::nullopt;
    }

    DWORD status = 0, sz = sizeof(status);
    WinHttpQueryHeaders(req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &status, &sz,
                        WINHTTP_NO_HEADER_INDEX);

    std::string result;
    for (;;) {
        DWORD avail = 0;
        if (!WinHttpQueryDataAvailable(req, &avail)) break;
        if (avail == 0) break;
        std::string chunk(avail, '\0');
        DWORD read = 0;
        if (!WinHttpReadData(req, chunk.data(), avail, &read)) break;
        result.append(chunk, 0, read);
    }
    if (status < 200 || status >= 300) {
        if (err)
            *err = std::format("HTTP {}: {}", status,
                               result.substr(0, 300));
        return std::nullopt;
    }
    return result;
}

}  // namespace

bool Agent::ready() const { return !config().cerebras.api_key.empty(); }

std::vector<ChatEntry> Agent::history() const {
    std::lock_guard lock(mu_);
    return visible_;
}

void Agent::clear() {
    std::lock_guard lock(mu_);
    messages_ = nlohmann::json::array();
    visible_.clear();
}

void Agent::push_visible(std::string role, std::string text) {
    std::lock_guard lock(mu_);
    visible_.push_back({std::move(role), std::move(text)});
    if (visible_.size() > 200)
        visible_.erase(visible_.begin(), visible_.begin() + 50);
}

void Agent::trim_context() {
    // Keep the system prompt plus roughly history_turns exchanges. Never
    // leave a role:"tool" message first — it would be orphaned from the
    // assistant message that requested it and the API rejects that.
    const size_t keep = 1 + (size_t)config().assistant.history_turns * 4;
    std::lock_guard lock(mu_);
    while (messages_.size() > 1 &&
           (messages_.size() > keep ||
            messages_[1].value("role", "") == "tool"))
        messages_.erase(messages_.begin() + 1);
}

bool Agent::ask_async(const std::string& text) {
    if (busy_ || text.empty()) return false;
    if (!ready()) return false;
    bool expected = false;
    if (!busy_.compare_exchange_strong(expected, true)) return false;
    std::thread([this, text] {
        auto [ok, reply] = run_turn(text);
        (void)ok;
        app().robot().set_state("idle");
        busy_ = false;
    }).detach();
    return true;
}

std::pair<bool, std::string> Agent::ask_sync(const std::string& text) {
    if (text.empty()) return {false, "empty prompt"};
    if (!ready()) return {false, "no Cerebras API key configured"};
    bool expected = false;
    if (!busy_.compare_exchange_strong(expected, true))
        return {false, "agent is busy with another request"};
    auto result = run_turn(text);
    busy_ = false;
    return result;
}

// Run one Bitdeer sub-agent: a single non-tool chat completion with the
// sub-agent's own model + specialty prompt.
std::pair<bool, std::string> call_subagent(const Config::SubAgent& sa,
                                           const std::string& task,
                                           const std::string& context) {
    const auto& cfg = config().bitdeer;
    if (cfg.api_key.empty()) return {false, "no Bitdeer API key configured"};

    std::string user = task;
    if (!context.empty()) user += "\n\nContext:\n" + context;

    nlohmann::json body = {
        {"model", sa.model},
        {"messages",
         nlohmann::json::array(
             {{{"role", "system"},
               {"content", sa.prompt.empty()
                               ? "You are a helpful assistant."
                               : sa.prompt}},
              {{"role", "user"}, {"content", user}}})},
        {"max_tokens", cfg.max_tokens},
        {"temperature", cfg.temperature},
        {"stream", false},
    };
    std::string err;
    auto raw = post_json(cfg.base_url, "/chat/completions", cfg.api_key, body,
                         cfg.timeout_seconds, &err);
    if (!raw) return {false, "Bitdeer: " + err};
    auto resp = nlohmann::json::parse(*raw, nullptr, false);
    if (resp.is_discarded()) return {false, "Bitdeer: invalid JSON"};
    const auto& choices = resp["choices"];
    if (!choices.is_array() || choices.empty())
        return {false, "Bitdeer: empty response"};
    const auto& msg = choices[0]["message"];
    if (msg.contains("content") && msg["content"].is_string())
        return {true, msg["content"].get<std::string>()};
    return {false, "Bitdeer: no content"};
}

// Find the configured sub-agent behind an ask_<name> tool call.
const Config::SubAgent* find_subagent(const std::string& tool_name) {
    if (tool_name.rfind("ask_", 0) != 0) return nullptr;
    const std::string want = tool_name.substr(4);
    for (const auto& sa : config().bitdeer.agents)
        if (sanitize_tool_name(sa.name) == want) return &sa;
    return nullptr;
}

std::optional<nlohmann::json> Agent::chat_complete(const nlohmann::json& messages,
                                                   std::string* err) {
    const auto& cfg = config().cerebras;
    nlohmann::json all_tools = tools::schemas();
    for (const auto& t : subagent_tools()) all_tools.push_back(t);
    nlohmann::json body = {
        {"model", cfg.model},
        {"messages", messages},
        {"tools", all_tools},
        {"tool_choice", "auto"},
        {"parallel_tool_calls", false},  // unsupported by gpt-oss-120b
        {"temperature", cfg.temperature},
        {"max_completion_tokens", cfg.max_tokens},
    };
    if (!cfg.reasoning_effort.empty())
        body["reasoning_effort"] = cfg.reasoning_effort;

    auto raw = post_json(cfg.base_url, "/chat/completions", cfg.api_key, body,
                         cfg.timeout_seconds, err);
    if (!raw) return std::nullopt;
    auto parsed = nlohmann::json::parse(*raw, nullptr, false);
    if (parsed.is_discarded()) {
        if (err) *err = "invalid JSON from Cerebras";
        return std::nullopt;
    }
    return parsed;
}

std::pair<bool, std::string> Agent::run_turn(const std::string& text) {
    push_visible("user", text);
    app().robot().set_state("thinking");

    {
        std::lock_guard lock(mu_);
        if (messages_.empty()) {
            std::string sys = kSystemPrompt;
            char user[64]{}; DWORD un = sizeof(user);
            if (GetUserNameA(user, &un))
                sys += std::format("\nWindows user: {}", user);
            messages_.push_back({{"role", "system"}, {"content", sys}});
        }
        messages_.push_back({{"role", "user"}, {"content", text}});
    }

    for (int round = 0; round < kMaxRounds; ++round) {
        nlohmann::json snapshot;
        {
            std::lock_guard lock(mu_);
            snapshot = messages_;
        }

        std::string err;
        auto resp = chat_complete(snapshot, &err);
        if (!resp) {
            push_visible("error", "Cerebras: " + err);
            app().robot().set_state("idle");
            return {false, "Cerebras error: " + err};
        }

        auto& choices = (*resp)["choices"];
        if (!choices.is_array() || choices.empty()) {
            push_visible("error", "Cerebras returned no choices");
            return {false, "empty response from Cerebras"};
        }
        const auto& msg = choices[0]["message"];

        {
            std::lock_guard lock(mu_);
            messages_.push_back(msg);
        }

        const auto& calls = msg["tool_calls"];
        if (!calls.is_array() || calls.empty()) {
            std::string reply;
            if (msg.contains("content") && msg["content"].is_string())
                reply = msg["content"].get<std::string>();
            if (reply.empty()) reply = "(no reply)";
            push_visible("assistant", reply);
            trim_context();
            return {true, truncate(reply, kReplyCap)};
        }

        // Execute each tool call sequentially and feed results back.
        for (const auto& call : calls) {
            const std::string id = call.value("id", "");
            const std::string name =
                call.value("function", nlohmann::json{})
                    .value("name", std::string{});
            const std::string args_raw =
                call.value("function", nlohmann::json{})
                    .value("arguments", std::string{"{}"});
            nlohmann::json args =
                nlohmann::json::parse(args_raw, nullptr, false);
            if (args.is_discarded()) args = nlohmann::json::object();

            push_visible("tool", "tool: " + name);
            log::info(std::format("agent tool call: {} {}", name, args_raw));

            std::string content;
            bool ok = false;
            if (const Config::SubAgent* sa = find_subagent(name)) {
                // ask_<name> — delegate to a Bitdeer sub-agent.
                auto [sok, reply] =
                    call_subagent(*sa, str_arg(args, "task"),
                                  str_arg(args, "context"));
                ok = sok;
                content = sok ? reply : "error: " + reply;
            } else {
                tools::Result r = tools::execute(name, args);
                ok = r.ok;
                content = r.ok ? (r.output.empty() ? "ok" : r.output)
                               : "error: " + r.output;
            }
            if (!ok) {
                // gpt-oss can hallucinate tool names — tell it plainly so it
                // self-corrects instead of retrying the same call.
                content += " (if this tool does not exist, do not call it again)";
            }
            push_visible("tool", std::format("   {} {}", ok ? "ok:" : "err:",
                                             truncate(content, 200)));

            std::lock_guard lock(mu_);
            messages_.push_back({{"role", "tool"},
                                 {"tool_call_id", id},
                                 {"content", truncate(content, kToolOutputCap)}});
        }
    }

    trim_context();
    push_visible("error", "hit the tool-call limit for this turn");
    return {false, "stopped after too many tool calls"};
}

}  // namespace argos::agent
