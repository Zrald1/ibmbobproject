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
- If a task is impossible (missing app, no permission), say so plainly instead of pretending.)";

std::string truncate(const std::string& s, size_t cap) {
    if (s.size() <= cap) return s;
    return s.substr(0, cap) + std::format("\n… [{} bytes truncated]", s.size() - cap);
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

std::optional<nlohmann::json> Agent::chat_complete(const nlohmann::json& messages,
                                                   std::string* err) {
    const auto& cfg = config().cerebras;
    nlohmann::json body = {
        {"model", cfg.model},
        {"messages", messages},
        {"tools", tools::schemas()},
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

            tools::Result r = tools::execute(name, args);
            std::string content =
                r.ok ? (r.output.empty() ? "ok" : r.output)
                     : "error: " + r.output;
            if (!r.ok) {
                // gpt-oss can hallucinate tool names — tell it plainly so it
                // self-corrects instead of retrying the same call.
                content += " (if this tool does not exist, do not call it again)";
            }
            push_visible("tool", std::format("   {} {}", r.ok ? "ok:" : "err:",
                                             truncate(r.output, 200)));

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
