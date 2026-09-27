#include "agent/agent.h"

#include <windows.h>

#include <winhttp.h>

#include <algorithm>
#include <chrono>
#include <format>
#include <thread>
#include <unordered_set>
#include <vector>

#include "bridge/ide_bridge.h"
#include "core/app.h"
#include "core/config.h"
#include "core/log.h"
#include "mcp/mcp_client.h"
#include "platform/win_util.h"
#include "tools/tools.h"
#include "voice/voice.h"

namespace argos::agent {
namespace {

constexpr size_t kToolOutputCap = 6000;   // chars of tool output fed back
constexpr size_t kReplyCap = 4000;        // phone replies stay short
// Not a tool-call limit — a hang guard. Bails only when the model repeats the
// exact same call over and over with no progress.
constexpr int kStallGuard = 8;

const char* kSystemPrompt = R"(You are Argos, a small robot companion living on the user's Windows desktop. You can act on the machine directly through tools.

Environment: Windows 11. File tools accept absolute paths or paths relative to the IDE workspace / current directory.

Rules:
- Never guess paths. When the user names a folder/file, call list_files on THAT path first; only search_files when listing didn't find it.
- Prefer read_file / search_files / list_files to inspect before changing anything. Read a file before editing it.
- For edits prefer apply_diff (surgical SEARCH/REPLACE) over write_file rewrites.
- delete_file requires confirmed=true — only set it when the user clearly asked for deletion.
- execute_command runs in a terminal (IDE terminal when an editor is connected, else cmd.exe). Keep commands non-interactive; prefer file tools over shell equivalents (list_files not dir, read_file not type).
- ide_chat sends a prompt into the IDE's own AI chatbox (Copilot/Cascade/Cline...) when the user wants you to talk to it; ide_chat_new starts a fresh session. open_in_editor/list_symbols/get_diagnostics drive the connected editor through the Argos extension.
- onboard_project kicks off repo onboarding: recon + a mission for the IDE's AI (IBM Bob), which writes AGENTS.md + ONBOARDING.md + a setup script and may extend ONBOARDING_CHECKLIST.md. When the user asks to be onboarded, call it.
- After onboarding, offer the proof steps: verify_setup runs the documented setup/build/test commands and ticks the checklist; codebase_tour returns key files — walk them with open_in_editor + a short speak() narration per stop; once ONBOARDING.md exists, show_onboarding_summary opens the floating glass report (markdown + mermaid diagram) — call it when the user wants the summary, and read highlights aloud too. speak talks immediately (queued), so keep each utterance to 1-2 sentences.
- When a tool call fails, adapt: check the error, try a different path or approach, don't blindly retry.
- "Analyze the codebase/repo/project" requests ALWAYS delegate — never answer that you cannot see the code. Pattern: propose_plan first (per-IDE roles), then on confirmation ide_dispatch the analysis to the connected IDE agents (each analyzes its workspace), wait_seconds, ide_status, and report per IDE. Use codebase_tour for a local map of key files and onboard_project when the repo has no ONBOARDING.md yet.
- Be concise in final answers — you are a desktop companion, not a document.
- If a task is impossible (missing app, no permission), say so plainly instead of pretending.
- You may delegate work to configured sub-agents (ask_* tools). Always restate the user's ORIGINAL request in the sub-agent's task plus everything it needs (paths, contents, constraints) — sub-agents are isolated and cannot see this conversation.)";

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

// One extra tool per configured sub-agent: ask_<name>(task, context?).
nlohmann::json subagent_tools() {
    nlohmann::json tools = nlohmann::json::array();
    for (const auto& sa : config().bitdeer.agents) {
        if (sa.name.empty()) continue;
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
                           "a complete, self-contained task including the "
                           "user's original request; it has the same file/IDE "
                           "tools and reports back to you.",
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

bool Agent::ready() const {
    return !config().cerebras.api_key.empty() ||
           (config().backend.enabled && !config().backend.base_url.empty());
}

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
        // A throw escaping a detached thread calls std::terminate and kills
        // the whole app with no crash log — keep the turn contained instead.
        try {
            auto [ok, reply] = run_turn(text);
            (void)ok;
            (void)reply;
        } catch (const std::exception& e) {
            log::error(std::string("agent turn threw: ") + e.what());
            push_visible("error", std::string("turn failed: ") + e.what());
        } catch (...) {
            log::error("agent turn threw (unknown exception)");
            push_visible("error", "turn failed (unknown exception)");
        }
        if (!voice::speaking())  // the TTS thread owns the state while talking
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
    std::pair<bool, std::string> result;
    try {
        result = run_turn(text);
    } catch (const std::exception& e) {
        log::error(std::string("agent turn threw: ") + e.what());
        result = {false, std::string("turn failed: ") + e.what()};
    } catch (...) {
        log::error("agent turn threw (unknown exception)");
        result = {false, "turn failed (unknown exception)"};
    }
    busy_ = false;
    return result;
}

// Execute one real tool (file/IDE/MCP) — shared by the orchestrator loop and
// the sub-agent loops.
std::pair<bool, std::string> run_tool(const std::string& name,
                                      const nlohmann::json& args) {
    if (name.rfind("mcp_", 0) == 0) {
        auto [ok, out] = mcp::client().call(name, args);
        return {ok, ok ? out : "error: " + out};
    }
    tools::Result r = tools::execute(name, args);
    return {r.ok, r.ok ? (r.output.empty() ? "ok" : r.output)
                       : "error: " + r.output};
}

// Run one Bitdeer sub-agent as its own agent loop: it gets the same file/IDE
// tool surface (read/search/list/open_in_editor/diagnostics/ide_chat/...) so
// it can navigate the workspace itself, but no ask_* tools — sub-agents
// cannot spawn other sub-agents.
std::pair<bool, std::string> call_subagent(const Config::SubAgent& sa,
                                           const std::string& task,
                                           const std::string& context) {
    const bool use_bitdeer = !config().bitdeer.api_key.empty();
    const std::string base_url = use_bitdeer ? config().bitdeer.base_url : config().cerebras.base_url;
    const std::string api_key = use_bitdeer ? config().bitdeer.api_key : config().cerebras.api_key;
    const std::string model = use_bitdeer ? sa.model : config().cerebras.model;
    const int timeout_s = use_bitdeer ? config().bitdeer.timeout_seconds : config().cerebras.timeout_seconds;
    const int max_tokens = use_bitdeer ? config().bitdeer.max_tokens : config().cerebras.max_tokens;
    const double temperature = use_bitdeer ? config().bitdeer.temperature : config().cerebras.temperature;

    if (api_key.empty()) return {false, "no API key configured for sub-agent " + sa.name};

    std::string sys = sa.prompt.empty()
                          ? "You are a specialized sub-agent helping Argos, a desktop robot companion."
                          : sa.prompt;
    sys += R"(

You are operating on Windows 11. You have real tools: read_file, search_files, list_files, file_stat, open_in_editor, get_diagnostics, list_symbols, execute_command, ide_chat and any mcp_* tools. Use them to inspect the actual workspace/IDE before answering — do not guess at file contents.

- Prefer inspecting over asking. Report concrete findings (paths, snippets, diagnostics).
- For changes, either describe the exact edit for the caller to apply, or apply it yourself with write_file/apply_diff when the task asks for real changes.
- Return one concise final report with no tool calls when done.)";

    std::string user = task;
    if (!context.empty()) user += "\n\nContext:\n" + context;

    nlohmann::json messages = nlohmann::json::array(
        {{{"role", "system"}, {"content", sys}},
         {{"role", "user"}, {"content", user}}});

    nlohmann::json sub_tools = tools::schemas();
    for (const auto& t : mcp::client().tool_schemas()) sub_tools.push_back(t);

    std::string last_sig;
    int same_calls = 0;
    bool tools_broken = false;  // provider rejected the tools param
    for (;;) {
        nlohmann::json body = {
            {"model", model},
            {"messages", messages},
            {"max_tokens", max_tokens},
            {"temperature", temperature},
            {"stream", false},
        };
        if (!tools_broken) {
            body["tools"] = sub_tools;
            body["tool_choice"] = "auto";
        }
        std::string err;
        auto raw = post_json(base_url, "/chat/completions", api_key,
                             body, timeout_s, &err);
        if (!raw && !tools_broken) {
            // Some catalog models don't support function calling — retry once without tools
            log::warn(std::format("sub-agent {}: tool-call request failed "
                                  "({}) — retrying without tools",
                                  sa.name, err));
            tools_broken = true;
            continue;
        }
        if (!raw) return {false, sa.name + " execution error: " + err};
        auto resp = nlohmann::json::parse(*raw, nullptr, false);
        if (resp.is_discarded()) return {false, sa.name + ": invalid JSON"};
        const auto& choices = resp["choices"];
        if (!choices.is_array() || choices.empty())
            return {false, sa.name + ": empty response"};
        const auto& msg = choices[0]["message"];
        messages.push_back(msg);

        const auto& calls = msg["tool_calls"];
        if (!calls.is_array() || calls.empty()) {
            if (msg.contains("content") && msg["content"].is_string())
                return {true, msg["content"].get<std::string>()};
            return {false, sa.name + ": no content"};
        }

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

            // identical-call hang guard (not a call limit)
            const std::string sig = name + " " + args_raw;
            same_calls = (sig == last_sig) ? same_calls + 1 : 0;
            last_sig = sig;
            if (same_calls >= kStallGuard)
                return {false, "sub-agent " + sa.name + " stalled repeating " + name};

            log::info(std::format("sub-agent {} tool call: {} {}", sa.name,
                                  name, args_raw));
            auto [ok, content] = run_tool(name, args);
            if (!ok) content += " (if this tool does not exist, do not call it again)";
            messages.push_back({{"role", "tool"},
                                {"tool_call_id", id},
                                {"content", truncate(content, kToolOutputCap)}});
        }
    }
}

// Find the configured sub-agent behind an ask_<name> tool call.
const Config::SubAgent* find_subagent(const std::string& tool_name) {
    if (tool_name.rfind("ask_", 0) != 0) return nullptr;
    const std::string want = tool_name.substr(4);
    for (const auto& sa : config().bitdeer.agents)
        if (sanitize_tool_name(sa.name) == want) return &sa;
    return nullptr;
}

// Rebuild the outgoing history so it is always API-legal:
//  - drop role:"tool" messages whose tool_call_id was never requested
//  - an assistant message with tool_calls must be followed by a tool reply
//    for EVERY id — synthesize an "interrupted" result for any gaps
// Without this, an aborted tool round leaves the next request a 400.
nlohmann::json sanitize_history(const nlohmann::json& in) {
    nlohmann::json out = nlohmann::json::array();
    std::vector<std::string> pending;  // tool_call ids awaiting replies
    auto flush_pending = [&] {
        for (const auto& id : pending)
            out.push_back({{"role", "tool"},
                           {"tool_call_id", id},
                           {"content", "error: call interrupted"}});
        pending.clear();
    };
    for (const auto& m : in) {
        const std::string role = m.value("role", "");
        if (role == "tool") {
            const std::string id = m.value("tool_call_id", "");
            auto it = std::find(pending.begin(), pending.end(), id);
            if (it == pending.end()) continue;  // orphan → drop
            pending.erase(it);
            out.push_back(m);
            continue;
        }
        // Any non-tool message ends the tool-reply window.
        flush_pending();
        out.push_back(m);
        if (role == "assistant") {
            for (const auto& c :
                 m.value("tool_calls", nlohmann::json::array()))
                pending.push_back(c.value("id", ""));
        }
    }
    flush_pending();  // trailing unanswered calls at the tail
    return out;
}

// Compact tool manifest for the backend brain. /api/chat has no tools array,
// so the model calls tools by replying with ONLY {"tool":"<name>","args":{..}}
// — chat_complete() translates that into a real tool_calls turn for the loop.
std::string backend_tool_manifest() {
    std::string ctx =
        "Argos Desktop tools. To call one reply with ONLY this JSON: "
        "{\"tool\":\"<name>\",\"args\":{...}} — otherwise answer normally. "
        "Never use XML <tool_call> syntax.\n"
        "Tools: ide_status{} status of EVERY connected IDE (workspace, active "
        "file, open tabs, chat providers) · ide_chat{text,target} chat an "
        "IDE · ide_dispatch{tasks:[{target,text,provider}]} send tasks to "
        "MULTIPLE IDEs at once (target='bob'|'code'|pid|'all') · "
        "wait_seconds{seconds} pause so IDEs can work · onboard_project{path} "
        "repo onboarding via the IDE · show_onboarding_summary{} glass summary "
        "popup · codebase_tour · verify_setup · backend_status · speak{text} · "
        "secret_scan{} find leaked API keys in the repo before pushing · "
        "git_commit{message} stage+commit the workspace · "
        "read_file/write_file/list_files/search_files/open_in_editor/"
        "execute_command/get_diagnostics{...} IDE file+terminal ops (optional "
        "'target' picks the IDE).\n"
        "Workflow for IDE tasks: for anything spanning multiple steps or "
        "multiple IDEs, FIRST call propose_plan{steps:[{target,task}]} and "
        "wait for the user to confirm; then ide_dispatch or ide_chat, then "
        "wait_seconds so the IDE assistant works, then ide_status to collect "
        "each IDE's state, then report the outcome per IDE to the user. Before "
        "any git push: secret_scan, then git_commit only if asked.\n"
        "\"Analyze the codebase/repo\" requests ALWAYS delegate — never say "
        "you cannot see the code: propose_plan with one analysis task per "
        "connected IDE (target='bob'/'code'/terminal title), ide_dispatch on "
        "confirmation, wait_seconds, ide_status, then report per IDE. "
        "codebase_tour{} gives a local map of key files; onboard_project{} "
        "when the repo has no ONBOARDING.md yet.\n"
        "Connected IDEs: ";
    auto eps = bridge::IdeBridge::enumerate();
    if (eps.empty()) {
        ctx += "none";
    } else {
        for (size_t i = 0; i < eps.size(); ++i)
            ctx += (i ? ", " : "") + eps[i].ide + " (pid " +
                   std::to_string(eps[i].pid) + ", port " +
                   std::to_string(eps[i].port) + ")";
    }
    // Terminal windows + npm AI CLIs (IBM Bob CLI, Gemini CLI, Claude Code…)
    // are dispatchable targets too — matched by a substring of the window
    // title, e.g. target='bob' or target='gemini'.
    ctx += "\nTerminals/CLI agents: ";
    auto fleet = tools::fleet_summary();
    bool any = false;
    for (const auto& t : fleet.value("terminals", nlohmann::json::array())) {
        ctx += (any ? ", " : "") + t.value("title", std::string("?"));
        any = true;
    }
    for (const auto& c : fleet.value("clis", nlohmann::json::array())) {
        if (!c.value("running", false)) continue;
        ctx += (any ? ", " : "") + c.value("name", std::string("?")) +
               " (pid " + std::to_string((unsigned long)c.value("pid", 0)) + ")";
        any = true;
    }
    if (!any) ctx += "none";
    return ctx;
}

// First complete, balanced JSON object in `text` (prose-safe: walks braces
// while respecting string literals). Returns nullopt when there is none or it
// is not valid JSON — never throws.
std::optional<nlohmann::json> first_json_object(const std::string& text) {
    const size_t open = text.find('{');
    if (open == std::string::npos) return std::nullopt;
    int depth = 0;
    bool instr = false, esc = false;
    for (size_t i = open; i < text.size(); ++i) {
        const char c = text[i];
        if (instr) {
            if (esc) esc = false;
            else if (c == '\\') esc = true;
            else if (c == '"') instr = false;
            continue;
        }
        if (c == '"') instr = true;
        else if (c == '{') ++depth;
        else if (c == '}' && --depth == 0) {
            auto j = nlohmann::json::parse(
                text.substr(open, i - open + 1), nullptr, false);
            if (j.is_discarded()) return std::nullopt;
            return j;
        }
    }
    return std::nullopt;
}

// XML-ish tool-call syntax some models fall back to even when told to emit
// JSON: <tool_call><function=NAME>{...}</function></tool_call>. Args may be a
// JSON object or absent. Never throws.
std::optional<std::pair<std::string, nlohmann::json>> xml_tool_call(
    const std::string& text) {
    const size_t f = text.find("<function=");
    if (f == std::string::npos) return std::nullopt;
    const size_t name_start = f + 10;
    const size_t name_end = text.find_first_of(">\n", name_start);
    if (name_end == std::string::npos) return std::nullopt;
    std::string name = text.substr(name_start, name_end - name_start);
    while (!name.empty() && (name.back() == ' ' || name.back() == '\r' ||
                             name.back() == '"'))
        name.pop_back();
    if (name.empty() || name.size() > 64) return std::nullopt;
    nlohmann::json args = nlohmann::json::object();
    if (auto j = first_json_object(text.substr(name_end)); j && j->is_object())
        args = *j;
    return std::make_pair(name, args);
}

// If the backend's reply is a tool-call JSON object, reshape it into the
// OpenAI tool_calls message the agent loop already knows how to execute.
nlohmann::json reply_as_completion(const std::string& text) {
    std::string name;
    nlohmann::json args = nlohmann::json::object();
    if (auto j = first_json_object(text); j && j->is_object()) {
        name = j->value("tool", j->value("name", std::string{}));
        if (!name.empty()) {
            args = j->contains("args") && (*j)["args"].is_object() ? (*j)["args"]
                   : j->contains("arguments") && (*j)["arguments"].is_object()
                       ? (*j)["arguments"] : nlohmann::json::object();
        }
    }
    if (name.empty()) {
        if (auto x = xml_tool_call(text)) {
            name = x->first;
            args = x->second;
        }
    }
    if (!name.empty()) {
        static int call_id = 0;
        return nlohmann::json{
            {"choices",
             nlohmann::json::array({{{"message",
                                      {{"role", "assistant"},
                                       {"tool_calls",
                                        nlohmann::json::array(
                                            {{{"id", std::format("bc{}", ++call_id)},
                                              {"type", "function"},
                                              {"function",
                                               {{"name", name},
                                                {"arguments", args.dump()}}}}})}}}}})}};
    }
    return nlohmann::json{
        {"choices", nlohmann::json::array({{{"message",
                                            {{"role", "assistant"},
                                             {"content", text}}}}})}};
}

// Relay chat through the user's own backend (java-backend / FastAPI) — the
// backend owns the provider keys. Tool context rides in screen_context (the
// backend injects it as a system message); replies that parse as tool-call
// JSON become real tool_calls for the agent loop.
std::optional<nlohmann::json> backend_chat(const nlohmann::json& messages,
                                           std::string* err) {
    const auto& b = config().backend;

    // History: user/assistant turns verbatim; tool results fold in as user
    // lines (providers reject a bare tool role without a matching tool_call).
    nlohmann::json history = nlohmann::json::array();
    std::string message;
    for (const auto& m : messages) {
        const std::string role = m.value("role", "");
        std::string c = m.value("content", "");
        if (c.empty()) continue;
        if (c.size() > 1500) c = c.substr(0, 1500) + "…";
        if (role == "user") {
            history.push_back({{"role", "user"}, {"content", c}});
            message = c;
        } else if (role == "assistant") {
            history.push_back({{"role", "assistant"}, {"content", c}});
        } else if (role == "tool") {
            const std::string tn = m.value("name", std::string("tool"));
            const std::string line =
                "[tool result " + tn + "] " + c.substr(0, 1200);
            history.push_back({{"role", "user"}, {"content", line}});
            message = line + " — continue the plan or answer the user now.";
        }
    }
    if (message.empty()) message = "hello";
    if (message.size() > 1900) message = message.substr(0, 1900);
    // Keep history bounded — the provider gets the whole list each call.
    while (history.size() > 24) history.erase(history.begin());

    nlohmann::json body{{"message", message},
                        {"history", history},
                        {"screen_context", backend_tool_manifest()}};
    if (!b.default_model.empty()) body["model"] = b.default_model;
    auto raw = post_json(b.base_url, "/api/chat", b.api_key, body,
                         b.timeout_seconds, err);
    if (!raw) return std::nullopt;
    auto resp = nlohmann::json::parse(*raw, nullptr, false);
    if (!resp.is_object()) {
        if (err) *err = "invalid JSON from backend";
        return std::nullopt;
    }
    // Field names differ per backend build and may carry non-string values —
    // only accept actual strings so a shape change can never throw here.
    std::string text;
    for (const char* k : {"reply", "response", "message"})
        if (resp.contains(k) && resp[k].is_string()) {
            text = resp[k].get<std::string>();
            break;
        }
    // Backend replies may carry expression/gesture tags meant for the mobile
    // client ([TOOL:EXPR:TALKING] etc.) — strip them for desktop display.
    for (size_t p;;) {
        p = text.find("[TOOL:");
        if (p == std::string::npos) break;
        const size_t close = text.find(']', p);
        if (close == std::string::npos) break;
        text.erase(p, close - p + 1);
    }
    while (!text.empty() && (text.back() == ' ' || text.back() == '\n'))
        text.pop_back();
    if (text.empty()) {
        if (err) *err = "backend returned an empty reply";
        return std::nullopt;
    }
    return reply_as_completion(text);
}

std::optional<nlohmann::json> Agent::chat_complete(const nlohmann::json& messages,
                                                   std::string* err) {
    const auto& cfg = config().cerebras;
    // Backend mode on → route through the relay; direct key is the fallback.
    if (config().backend.enabled && !config().backend.base_url.empty()) {
        if (auto r = backend_chat(messages, err)) return r;
        if (cfg.api_key.empty()) return std::nullopt;
        if (err) err->clear();  // backend failed — try the direct key
    }
    nlohmann::json all_tools = tools::schemas();
    for (const auto& t : subagent_tools()) all_tools.push_back(t);
    for (const auto& t : mcp::client().tool_schemas()) all_tools.push_back(t);
    nlohmann::json body = {
        {"model", cfg.model},
        {"messages", sanitize_history(messages)},
        {"tools", all_tools},
        {"tool_choice", "auto"},
        {"parallel_tool_calls", false},  // unsupported by gpt-oss-120b
        {"temperature", cfg.temperature},
        {"max_completion_tokens", cfg.max_tokens},
    };
    // reasoning_effort is model-dependent: gpt-oss accepts low/medium/high,
    // GLM/Gemma also accept "none". Never send an invalid value — Cerebras
    // rejects the whole request with a 400.
    if (!cfg.reasoning_effort.empty()) {
        const bool is_gpt_oss = cfg.model.find("gpt-oss") != std::string::npos;
        const std::string& e = cfg.reasoning_effort;
        const bool ok = is_gpt_oss
            ? (e == "low" || e == "medium" || e == "high")
            : (e == "none" || e == "low" || e == "medium" || e == "high");
        if (ok) body["reasoning_effort"] = e;
    }

    // 429 retry: the free tier throttles on requests/min AND input
    // tokens/min. Back off and retry instead of failing the whole turn.
    for (int attempt = 0; attempt < 4; ++attempt) {
        auto raw = post_json(cfg.base_url, "/chat/completions", cfg.api_key,
                             body, cfg.timeout_seconds, err);
        if (raw) {
            auto parsed = nlohmann::json::parse(*raw, nullptr, false);
            if (parsed.is_discarded()) {
                if (err) *err = "invalid JSON from Cerebras";
                return std::nullopt;
            }
            return parsed;
        }
        const bool limited = err && err->find("HTTP 429") != std::string::npos;
        if (!limited || attempt == 3) return std::nullopt;
        const int wait_s = 15 * (attempt + 1);  // 15s, 30s, 45s
        log::warn(std::format("cerebras: 429 rate-limited, retrying in {}s",
                              wait_s));
        std::this_thread::sleep_for(std::chrono::seconds(wait_s));
    }
    return std::nullopt;
}

// Refresh the chat-tab follow-up chips: ask the backend for 4 short prompts,
// parse the JSON array out of whatever it returns, fall back to the canned set
// if it can't produce one. Never throws; never blocks the reply itself.
void Agent::update_suggestions(const std::string& user_text,
                               const std::string& reply) {
    static const std::vector<std::string> kFallback = {
        "Show connected IDEs", "Onboard this repo",
        "Scan the repo for API leaks", "What can you do?"};
    std::vector<std::string> sug = kFallback;
    const auto& b = config().backend;
    if (b.enabled && !b.base_url.empty()) {
        std::string err;
        const std::string prompt =
            "Suggest exactly 4 short follow-up prompts (under 8 words each) "
            "the user might ask next. Reply with ONLY a JSON array of strings, "
            "no other text. User asked: \"" + user_text.substr(0, 160) +
            "\" You answered: \"" + reply.substr(0, 240) + "\"";
        if (auto raw = post_json(
                b.base_url, "/api/chat", b.api_key,
                nlohmann::json{{"message", prompt},
                               {"history", nlohmann::json::array()}},
                20, &err)) {
            auto resp = nlohmann::json::parse(*raw, nullptr, false);
            std::string t;
            if (resp.is_object()) {
                for (const char* k : {"reply", "response"})
                    if (resp.contains(k) && resp[k].is_string()) {
                        t = resp[k].get<std::string>();
                        break;
                    }
            }
            const size_t l = t.find('['), r = t.rfind(']');
            if (l != std::string::npos && r > l) {
                auto arr = nlohmann::json::parse(t.substr(l, r - l + 1),
                                                 nullptr, false);
                std::vector<std::string> tmp;
                if (arr.is_array())
                    for (const auto& s : arr)
                        if (s.is_string() && !s.get<std::string>().empty())
                            tmp.push_back(s.get<std::string>());
                if (!tmp.empty()) {
                    tmp.resize(std::min<size_t>(4, tmp.size()));
                    sug = std::move(tmp);
                }
            }
        }
    }
    std::lock_guard lock(mu_);
    suggestions_ = std::move(sug);
}

std::pair<bool, std::string> Agent::run_turn(const std::string& text) {
    push_visible("user", text);
    app().robot().notify_activity();
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
        // Pin the delegation rule right on the task — a distant system prompt
    // gets ignored once the conversation grows, this doesn't.
    std::string stamped = text;
    if (!config().bitdeer.agents.empty() &&
        !config().bitdeer.api_key.empty()) {
        std::string names;
        for (const auto& sa : config().bitdeer.agents)
            names += "ask_" + sanitize_tool_name(sa.name) + " ";
        stamped += "\n\n[orchestrator rule: if fulfilling this needs more "
                   "than a single quick lookup, your FIRST tool call must be "
                   "one of: " + names + "— delegate, wait for the reply, then "
                   "respond. Never do multi-step work yourself.]";
    }
    messages_.push_back({{"role", "user"}, {"content", stamped}});
    }

    // No tool-call limit — the loop runs until the model stops calling tools.
    // The stall guard only catches identical calls repeated in a row (a hang,
    // not work).
    std::string last_sig;
    int same_calls = 0;
    for (;;) {
        nlohmann::json snapshot;
        {
            std::lock_guard lock(mu_);
            snapshot = messages_;
        }

        std::string err;
        auto resp = chat_complete(snapshot, &err);
        if (!resp) {
            log::error("cerebras: " + err);
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
            app().robot().notify_activity();  // turn end = last activity point
            voice::speak_async(reply);  // voice reply
            update_suggestions(text, reply);
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

            const std::string sig = name + " " + args_raw;
            same_calls = (sig == last_sig) ? same_calls + 1 : 0;
            last_sig = sig;
            if (same_calls >= kStallGuard) {
                trim_context();
                push_visible("error", "stuck repeating the same call — stopped");
                return {false, "stalled: repeated identical tool call"};
            }

            std::string content;
            bool ok = false;
            if (const Config::SubAgent* sa = find_subagent(name)) {
                push_visible("tool", std::format("Argos delegated to {}: {}", sa->name, str_arg(args, "task")));
                log::info(std::format("delegating to sub-agent {} {}", sa->name, args_raw));
                auto [sok, reply] =
                    call_subagent(*sa, str_arg(args, "task"),
                                  str_arg(args, "context"));
                ok = sok;
                content = sok ? reply : "error: " + reply;
                push_visible("tool", std::format("[{} report] {}", sa->name, truncate(content, 300)));
            } else {
                push_visible("tool", "tool: " + name);
                log::info(std::format("agent tool call: {} {}", name, args_raw));
                auto [rok, out] = run_tool(name, args);
                ok = rok;
                content = out;
                if (!ok) {
                    content += " (if this tool does not exist, do not call it again)";
                }
                // Report-style tools (plans, fleet status, scans) are meant
                // to be read — don't clip them to the 200-char status line.
                static const std::unordered_set<std::string> kVerbose = {
                    "propose_plan", "ide_status", "ide_dispatch",
                    "secret_scan", "git_commit", "onboard_project",
                    "show_onboarding_summary", "codebase_tour",
                    "verify_setup", "backend_status", "wait_seconds"};
                const size_t cap = kVerbose.count(name) ? 4000 : 200;
                push_visible("tool", std::format("   {} {}", ok ? "ok:" : "err:",
                                                 truncate(content, cap)));
            }

            std::lock_guard lock(mu_);
            messages_.push_back({{"role", "tool"},
                                 {"name", name},
                                 {"tool_call_id", id},
                                 {"content", truncate(content, kToolOutputCap)}});
        }
    }
}

}  // namespace argos::agent
