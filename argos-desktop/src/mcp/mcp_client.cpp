#include "mcp/mcp_client.h"

#include <windows.h>

#include <winhttp.h>

#include <algorithm>
#include <chrono>
#include <format>
#include <memory>
#include <queue>
#include <vector>

#include "core/log.h"
#include "platform/win_util.h"

namespace argos::mcp {
namespace {

using nlohmann::json;
using namespace std::chrono_literals;

constexpr auto kRequestTimeout = 30s;
constexpr const char* kProtocolVersion = "2025-03-26";

std::string sanitize(const std::string& name) {
    std::string out;
    for (char c : name) {
        if (isalnum((unsigned char)c) || c == '_' || c == '-')
            out += (char)tolower((unsigned char)c);
        else
            out += '_';
    }
    return out.empty() ? "srv" : out;
}

// ── stdio transport ──────────────────────────────────────────────────────
// Spawns the server binary with its stdin/stdout redirected to our pipes.
// MCP stdio framing is newline-delimited JSON-RPC 2.0 (UTF-8).
struct StdioProc {
    HANDLE in_write = nullptr;   // we write requests here
    HANDLE out_read = nullptr;   // we read responses/notifications here
    PROCESS_INFORMATION pi{};
    std::thread reader;

    std::mutex rmu;
    std::condition_variable rcv;
    std::map<int64_t, json> replies;
    std::atomic<bool> dead{false};

    bool spawn(const std::string& command, const std::string& args) {
        SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE};
        HANDLE in_read = nullptr, out_write = nullptr, err_write = nullptr;
        if (!CreatePipe(&in_read, &in_write, &sa, 0) ||
            !CreatePipe(&out_read, &out_write, &sa, 0)) {
            return false;
        }
        // Parent ends must NOT be inherited by the child.
        SetHandleInformation(in_write, HANDLE_FLAG_INHERIT, 0);
        SetHandleInformation(out_read, HANDLE_FLAG_INHERIT, 0);
        DuplicateHandle(GetCurrentProcess(), out_write, GetCurrentProcess(),
                        &err_write, 0, TRUE, DUPLICATE_SAME_ACCESS);

        std::wstring cmdline =
            win::to_wide(command + (args.empty() ? "" : " " + args));
        // Inherit ONLY the three stdio pipe ends — bInheritHandles=TRUE would
        // also leak the phone server's listen socket into the MCP child, which
        // keeps port 47830 zombie-bound after Argos exits.
        STARTUPINFOEXW si{};
        si.StartupInfo.cb = sizeof(si);
        si.StartupInfo.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
        si.StartupInfo.wShowWindow = SW_HIDE;
        si.StartupInfo.hStdInput = in_read;
        si.StartupInfo.hStdOutput = out_write;
        si.StartupInfo.hStdError = err_write;
        SIZE_T attr_size = 0;
        InitializeProcThreadAttributeList(nullptr, 1, 0, &attr_size);
        std::vector<BYTE> attrs(attr_size);
        auto* attr_list = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attrs.data());
        HANDLE inherit[] = {in_read, out_write, err_write};
        InitializeProcThreadAttributeList(attr_list, 1, 0, &attr_size);
        UpdateProcThreadAttribute(attr_list, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                                  inherit, sizeof(inherit), nullptr, nullptr);
        if (!CreateProcessW(nullptr, cmdline.data(), nullptr, nullptr, TRUE,
                          CREATE_NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT,
                          nullptr, nullptr, &si.StartupInfo, &pi)) {
            DeleteProcThreadAttributeList(attr_list);
            CloseHandle(in_read); CloseHandle(in_write);
            CloseHandle(out_read); CloseHandle(out_write);
            CloseHandle(err_write);
            in_write = out_read = nullptr;
            return false;
        }
        DeleteProcThreadAttributeList(attr_list);
        CloseHandle(in_read);
        CloseHandle(out_write);
        CloseHandle(err_write);

        reader = std::thread([this] { read_loop(); });
        return true;
    }

    void read_loop() {
        std::string buf;
        char chunk[8192];
        for (;;) {
            DWORD n = 0;
            if (!ReadFile(out_read, chunk, sizeof(chunk), &n, nullptr) ||
                n == 0)
                break;
            buf.append(chunk, n);
            size_t pos;
            while ((pos = buf.find('\n')) != std::string::npos) {
                std::string line = buf.substr(0, pos);
                buf.erase(0, pos + 1);
                on_line(line);
            }
        }
        dead = true;
        rcv.notify_all();
    }

    void on_line(const std::string& line) {
        if (line.empty() || line == "\r") return;
        json msg = json::parse(line, nullptr, false);
        if (msg.is_discarded()) return;
        auto it = msg.find("id");
        if (it != msg.end() && it->is_number_integer()) {
            std::lock_guard lock(rmu);
            replies[it->get<int64_t>()] = std::move(msg);
            rcv.notify_all();
        }
        // Notifications (progress, log messages) are ignored — Argos is a
        // client, not a host; tools/listChanged etc. need no reply.
    }

    // Send a JSON-RPC request and wait for the matching reply.
    std::optional<json> request(int64_t id, const std::string& method,
                                const json& params) {
        json msg = {{"jsonrpc", "2.0"},
                    {"id", id},
                    {"method", method},
                    {"params", params}};
        std::string line = msg.dump() + "\n";
        DWORD written = 0;
        if (!in_write ||
            !WriteFile(in_write, line.data(), (DWORD)line.size(), &written,
                       nullptr)) {
            return std::nullopt;
        }
        std::unique_lock lock(rmu);
        if (!rcv.wait_for(lock, kRequestTimeout,
                          [&] { return dead || replies.count(id) > 0; }))
            return std::nullopt;
        auto it = replies.find(id);
        if (it == replies.end()) return std::nullopt;
        json out = std::move(it->second);
        replies.erase(it);
        return out;
    }

    void notify(const std::string& method) {
        json msg = {{"jsonrpc", "2.0"}, {"method", method}};
        std::string line = msg.dump() + "\n";
        DWORD w = 0;
        if (in_write) WriteFile(in_write, line.data(), (DWORD)line.size(), &w,
                                nullptr);
    }

    void kill() {
        if (pi.hProcess) {
            TerminateProcess(pi.hProcess, 0);
            CloseHandle(pi.hProcess);
            CloseHandle(pi.hThread);
            pi.hProcess = pi.hThread = nullptr;
        }
        for (HANDLE* h : {&in_write, &out_read})
            if (*h) { CloseHandle(*h); *h = nullptr; }
        if (reader.joinable()) reader.join();
    }

    ~StdioProc() { kill(); }
};

// ── streamable-HTTP transport ────────────────────────────────────────────
// POST each JSON-RPC message to the endpoint; accept JSON or SSE replies;
// carry the Mcp-Session-Id the server hands out at initialize.
struct HttpTransport {
    std::string url;
    std::string bearer;
    std::string session_id;

    // POST one JSON-RPC message. Returns the response JSON, or nullopt.
    std::optional<json> send(const json& msg, std::string* sid_out = nullptr) {
        URL_COMPONENTSW uc{};
        uc.dwStructSize = sizeof(uc);
        wchar_t host[256]{}, path[1024]{}, scheme[16]{};
        uc.lpszHostName = host;  uc.dwHostNameLength = 256;
        uc.lpszUrlPath = path;   uc.dwUrlPathLength = 1024;
        uc.lpszScheme = scheme;  uc.dwSchemeLength = 16;
        std::wstring wurl = win::to_wide(url);
        if (!WinHttpCrackUrl(wurl.c_str(), 0, 0, &uc)) return std::nullopt;
        const bool https = uc.nScheme == INTERNET_SCHEME_HTTPS;

        HINTERNET session = WinHttpOpen(
            L"ArgosDesktop/0.1", WINHTTP_ACCESS_TYPE_NO_PROXY,
            WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
        if (!session) return std::nullopt;
        HINTERNET conn = nullptr, req = nullptr;
        struct G {
            HINTERNET&a; HINTERNET&b; HINTERNET&c;
            ~G() {
                if (c) WinHttpCloseHandle(c);
                if (b) WinHttpCloseHandle(b);
                if (a) WinHttpCloseHandle(a);
            }
        } g{session, conn, req};
        WinHttpSetTimeouts(session, 30000, 30000, 30000, 30000);
        conn = WinHttpConnect(session, host, uc.nPort, 0);
        if (!conn) return std::nullopt;
        req = WinHttpOpenRequest(
            conn, L"POST", std::wstring(path, uc.dwUrlPathLength).c_str(),
            nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
            https ? WINHTTP_FLAG_SECURE : 0);
        if (!req) return std::nullopt;

        std::wstring headers =
            L"Content-Type: application/json\r\n"
            L"Accept: application/json, text/event-stream\r\n";
        if (!bearer.empty())
            headers += L"Authorization: Bearer " + win::to_wide(bearer) +
                       L"\r\n";
        if (!session_id.empty())
            headers += L"Mcp-Session-Id: " + win::to_wide(session_id) +
                       L"\r\n";

        std::string payload = msg.dump();
        if (!WinHttpSendRequest(req, headers.c_str(), (DWORD)headers.size(),
                              payload.data(), (DWORD)payload.size(),
                              (DWORD)payload.size(), 0) ||
            !WinHttpReceiveResponse(req, nullptr))
            return std::nullopt;

        DWORD status = 0, sz = sizeof(status);
        WinHttpQueryHeaders(req,
                            WINHTTP_QUERY_STATUS_CODE |
                                WINHTTP_QUERY_FLAG_NUMBER,
                            WINHTTP_HEADER_NAME_BY_INDEX, &status, &sz,
                            WINHTTP_NO_HEADER_INDEX);

        // Capture the session id the server issues at initialize.
        if (sid_out) {
            wchar_t sid[256]{};
            DWORD slen = sizeof(sid);
            if (WinHttpQueryHeaders(
                    req, WINHTTP_QUERY_CUSTOM, L"Mcp-Session-Id", sid,
                    &slen, WINHTTP_NO_HEADER_INDEX))
                *sid_out = win::to_utf8(sid);
        }

        // Content type decides the framing.
        wchar_t ct[128]{};
        DWORD ctlen = sizeof(ct);
        bool sse = false;
        if (WinHttpQueryHeaders(req, WINHTTP_QUERY_CONTENT_TYPE, nullptr, ct,
                                &ctlen, WINHTTP_NO_HEADER_INDEX))
            sse = std::wstring(ct).find(L"text/event-stream") !=
                  std::wstring::npos;

        std::string body;
        for (;;) {
            DWORD avail = 0;
            if (!WinHttpQueryDataAvailable(req, &avail) || avail == 0) break;
            std::string chunk(avail, '\0');
            DWORD got = 0;
            if (!WinHttpReadData(req, chunk.data(), avail, &got)) break;
            body.append(chunk, 0, got);
        }
        if (status == 202 || body.empty()) return json::object();  // ack
        if (status < 200 || status >= 300) return std::nullopt;

        if (!sse) {
            json r = json::parse(body, nullptr, false);
            return r.is_discarded() ? std::nullopt : std::optional<json>(r);
        }
        // SSE: scan "data:" lines, take the last complete JSON-RPC message.
        std::optional<json> last;
        size_t p = 0;
        while (p < body.size()) {
            size_t nl = body.find('\n', p);
            std::string line =
                body.substr(p, nl == std::string::npos ? body.size() - p
                                                       : nl - p);
            p = (nl == std::string::npos) ? body.size() : nl + 1;
            if (line.rfind("data:", 0) == 0) {
                json r = json::parse(line.substr(5), nullptr, false);
                if (!r.is_discarded()) last = std::move(r);
            }
        }
        return last;
    }
};

}  // namespace

// ── per-server state ─────────────────────────────────────────────────────
struct McpClient::Server {
    Config::McpServer cfg;
    std::unique_ptr<StdioProc> proc;
    HttpTransport http;
    bool connected = false;
    std::string error;
    std::string server_info;
    std::map<std::string, json> tools;   // mcp_<srv>_<tool> -> {orig, schema}
    std::atomic<int64_t> next_id{1};
    std::mutex send_mu;                  // serialize requests per server

    bool connect() {
        if (cfg.type == "http") {
            if (cfg.url.empty()) { error = "no url"; return false; }
            http.url = cfg.url;
            http.bearer = cfg.token;
        } else {
            if (cfg.command.empty()) { error = "no command"; return false; }
            proc = std::make_unique<StdioProc>();
            if (!proc->spawn(cfg.command, cfg.args)) {
                error = "spawn failed: " + cfg.command;
                return false;
            }
        }
        // initialize handshake
        auto resp = request("initialize",
                            {{"protocolVersion", kProtocolVersion},
                             {"capabilities", json::object()},
                             {"clientInfo",
                              {{"name", "argos-desktop"},
                               {"version", "1.0"}}}});
        if (!resp) { error = "initialize timed out"; return false; }
        if (resp->contains("error")) {
            error = "initialize: " + (*resp)["error"].dump();
            return false;
        }
        const auto& si = (*resp)["result"]["serverInfo"];
        server_info = si.value("name", std::string{}) + " " +
                      si.value("version", std::string{});
        notify("notifications/initialized");

        auto tl = request("tools/list", json::object());
        if (!tl || !(*tl)["result"].contains("tools")) {
            error = "tools/list failed";
            return false;
        }
        for (const auto& t : (*tl)["result"]["tools"]) {
            std::string tn = t.value("name", "");
            if (tn.empty()) continue;
            std::string pfx = "mcp_" + sanitize(cfg.name) + "_" +
                              sanitize(tn);
            tools[pfx] = {{"orig", tn}, {"schema", t}};
        }
        connected = true;
        return true;
    }

    std::optional<json> request(const std::string& method,
                                const json& params) {
        std::lock_guard lock(send_mu);
        if (proc) return proc->request(next_id++, method, params);
        return http.send({{"jsonrpc", "2.0"},
                          {"id", next_id++},
                          {"method", method},
                          {"params", params}},
                         &http.session_id);
    }

    void notify(const std::string& method) {
        std::lock_guard lock(send_mu);
        if (proc) { proc->notify(method); return; }
        http.send({{"jsonrpc", "2.0"}, {"method", method}}, nullptr);
    }

    void disconnect() {
        if (proc) { proc->kill(); proc.reset(); }
        connected = false;
        tools.clear();
    }
};

// ── McpClient ────────────────────────────────────────────────────────────
McpClient& McpClient::instance() {
    static McpClient c;
    return c;
}

void McpClient::connect_all() {
    if (connect_thread_.joinable()) connect_thread_.join();
    connecting_ = true;
    connect_thread_ = std::thread([this] {
        std::vector<Config::McpServer> want;
        for (const auto& s : config().mcp.servers)
            if (s.enabled && !s.name.empty()) want.push_back(s);

        std::vector<std::shared_ptr<Server>> fresh;
        for (const auto& cfg : want) {
            auto srv = std::make_shared<Server>();
            srv->cfg = cfg;
            log::info(std::format("mcp: connecting '{}' ({})...", cfg.name,
                                  cfg.type));
            if (srv->connect())
                log::info(std::format("mcp: '{}' connected — {} tools ({})",
                                      cfg.name, srv->tools.size(),
                                      srv->server_info));
            else
                log::warn(std::format("mcp: '{}' failed — {}", cfg.name,
                                      srv->error));
            fresh.push_back(std::move(srv));
        }
        {
            std::lock_guard lock(mu_);
            for (auto& old : servers_) old->disconnect();
            servers_ = std::move(fresh);
        }
        connecting_ = false;
    });
}

void McpClient::shutdown() {
    if (connect_thread_.joinable()) connect_thread_.join();
    std::lock_guard lock(mu_);
    for (auto& s : servers_) s->disconnect();
    servers_.clear();
}

nlohmann::json McpClient::tool_schemas() {
    std::lock_guard lock(mu_);
    json out = json::array();
    for (const auto& s : servers_) {
        if (!s->connected) continue;
        for (const auto& [pfx, meta] : s->tools) {
            const auto& sch = meta["schema"];
            json tool = {
                {"type", "function"},
                {"function",
                 {{"name", pfx},
                  {"description",
                   std::format("[MCP:{}] {}", s->cfg.name,
                               sch.value("description",
                                         std::string{"MCP tool"}))},
                  {"parameters",
                   sch.contains("inputSchema") ? sch["inputSchema"]
                                               : json{{"type", "object"},
                                                      {"properties",
                                                       json::object()}}}}}};
            out.push_back(std::move(tool));
        }
    }
    return out;
}

std::pair<bool, std::string> McpClient::call(const std::string& prefixed_name,
                                           const json& arguments) {
    std::shared_ptr<Server> srv;
    std::string orig;
    {
        std::lock_guard lock(mu_);
        for (const auto& s : servers_) {
            auto it = s->tools.find(prefixed_name);
            if (it != s->tools.end()) {
                srv = s;
                orig = it->second["orig"].get<std::string>();
                break;
            }
        }
    }
    if (!srv) return {false, "unknown MCP tool " + prefixed_name};
    if (!srv->connected) return {false, "MCP server disconnected"};

    auto resp = srv->request("tools/call",
                             {{"name", orig},
                              {"arguments", arguments.is_object()
                                                ? arguments
                                                : json::object()}});
    if (!resp) return {false, "MCP request timed out"};
    if (resp->contains("error"))
        return {false, "MCP error: " + (*resp)["error"].dump()};
    const auto& result = (*resp)["result"];
    if (result.value("isError", false)) {
        std::string why;
        for (const auto& c : result["content"])
            if (c.value("type", "") == "text")
                why += c.value("text", "") + " ";
        return {false, why.empty() ? "tool returned isError" : why};
    }
    // Flatten content blocks to plain text for the brain.
    std::string out;
    for (const auto& c : result["content"]) {
        std::string t = c.value("type", "");
        if (t == "text")
            out += c.value("text", "") + "\n";
        else
            out += "[" + t + " content]\n";
    }
    if (out.empty()) out = "ok";
    return {true, out};
}

std::vector<ServerStatus> McpClient::status() {
    std::lock_guard lock(mu_);
    std::vector<ServerStatus> out;
    for (const auto& s : servers_)
        out.push_back({s->cfg.name, s->cfg.type, s->connected,
                       (int)s->tools.size(), s->error, s->server_info});
    return out;
}

}  // namespace argos::mcp
