// WinHTTP implementation of the IDE bridge client. See ide_bridge.h.

#include "bridge/ide_bridge.h"

#include <windows.h>
#include <winhttp.h>

#include <fstream>
#include <map>
#include <sstream>

#include "core/log.h"
#include "platform/win_util.h"

namespace argos::bridge {
namespace {

constexpr int kTimeoutMs = 8000;

std::filesystem::path discovery_path() {
    return win::app_data_dir() / "ide-bridge.json";
}

std::optional<BridgeEndpoint> parse_endpoint_file(const std::filesystem::path& file) {
    std::ifstream in(file);
    if (!in) return std::nullopt;
    nlohmann::json info;
    try {
        in >> info;
    } catch (...) {
        return std::nullopt;
    }
    BridgeEndpoint ep;
    ep.port = info.value("port", 0);
    ep.token = info.value("token", "");
    ep.ide = info.value("ide", "");
    ep.version = info.value("version", "");
    ep.workspace = info.value("workspace", "");
    ep.pid = info.value("pid", 0);
    ep.main_pid = info.value("mainPid", ep.pid);
    if (ep.port <= 0 || ep.token.empty()) return std::nullopt;
    return ep;
}

}  // namespace

bool IdeBridge::refresh() {
    const auto ep = parse_endpoint_file(discovery_path());
    if (!ep) {
        connected_ = false;
        return false;
    }
    endpoint_ = *ep;
    // Cheap authenticated liveness check.
    connected_ = call("ide.ping").has_value();
    return connected_;
}

std::optional<nlohmann::json> IdeBridge::call(std::string_view method,
                                              const nlohmann::json& params) {
    last_error_.clear();
    nlohmann::json envelope{{"method", method}, {"params", params}};
    const auto body = http_post("/command", envelope.dump());
    if (!body) {
        last_error_ = "bridge unreachable";
        connected_ = false;
        return std::nullopt;
    }
    nlohmann::json response;
    try {
        response = nlohmann::json::parse(*body);
    } catch (...) {
        last_error_ = "malformed bridge response";
        return std::nullopt;
    }
    if (!response.value("ok", false)) {
        last_error_ = response.value("error", "unknown bridge error");
        return std::nullopt;
    }
    connected_ = true;
    return response.value("result", nlohmann::json::object());
}

std::optional<nlohmann::json> IdeBridge::call_on(
    const BridgeEndpoint& ep, std::string_view method,
    const nlohmann::json& params) {
    nlohmann::json envelope{{"method", method}, {"params", params}};
    const auto body = post_on(ep, "/command", envelope.dump());
    if (!body) return std::nullopt;
    nlohmann::json response;
    try {
        response = nlohmann::json::parse(*body);
    } catch (...) {
        return std::nullopt;
    }
    if (!response.value("ok", false)) return std::nullopt;
    return response.value("result", nlohmann::json::object());
}

std::vector<BridgeEndpoint> IdeBridge::enumerate() {
    std::map<int, BridgeEndpoint> by_pid;
    const auto dir = discovery_path().parent_path();
    std::vector<std::filesystem::path> files{discovery_path()};
    std::error_code ec;
    for (const auto& e :
         std::filesystem::directory_iterator(dir / "ide-bridge.d", ec)) {
        if (e.path().extension() == ".json") files.push_back(e.path());
    }
    for (const auto& f : files) {
        const auto ep = parse_endpoint_file(f);
        if (ep && !by_pid.count(ep->pid) && call_on(*ep, "ide.ping"))
            by_pid[ep->pid] = *ep;
    }
    std::vector<BridgeEndpoint> out;
    for (auto& [pid, ep] : by_pid) out.push_back(ep);
    return out;
}

std::vector<BridgeEndpoint> IdeBridge::registered() {
    std::map<int, BridgeEndpoint> by_pid;
    std::vector<std::filesystem::path> files{discovery_path()};
    std::error_code ec;
    for (const auto& e :
         std::filesystem::directory_iterator(discovery_path().parent_path() /
                                             "ide-bridge.d", ec)) {
        if (e.path().extension() == ".json") files.push_back(e.path());
    }
    for (const auto& f : files) {
        const auto ep = parse_endpoint_file(f);
        if (ep) by_pid[ep->pid] = *ep;
    }
    std::vector<BridgeEndpoint> out;
    for (auto& [pid, ep] : by_pid) out.push_back(ep);
    return out;
}

std::optional<std::string> IdeBridge::http_post(std::string_view path,
                                                std::string_view body) {
    return post_on(endpoint_, path, body, &last_error_);
}

std::optional<std::string> IdeBridge::post_on(const BridgeEndpoint& ep,
                                              std::string_view path,
                                              std::string_view body,
                                              std::string* err) {
    HINTERNET session =
        WinHttpOpen(L"ArgosDesktop/0.1", WINHTTP_ACCESS_TYPE_NO_PROXY,
                    WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) return std::nullopt;

    HINTERNET connection = nullptr, request = nullptr;
    std::optional<std::string> out;

    // Scope guard so every exit path cleans up.
    struct Guard {
        HINTERNET& a;
        HINTERNET& b;
        HINTERNET& c;
        ~Guard() {
            if (c) WinHttpCloseHandle(c);
            if (b) WinHttpCloseHandle(b);
            if (a) WinHttpCloseHandle(a);
        }
    } guard{session, connection, request};

    WinHttpSetTimeouts(session, kTimeoutMs, kTimeoutMs, kTimeoutMs, kTimeoutMs);

    connection = WinHttpConnect(session, L"127.0.0.1",
                                static_cast<INTERNET_PORT>(ep.port), 0);
    if (!connection) return std::nullopt;

    request = WinHttpOpenRequest(connection, L"POST", win::to_wide(path).c_str(),
                                 nullptr, WINHTTP_NO_REFERER,
                                 WINHTTP_DEFAULT_ACCEPT_TYPES, 0);
    if (!request) return std::nullopt;

    std::wstring headers = L"Content-Type: application/json\r\nAuthorization: Bearer ";
    headers += win::to_wide(ep.token);
    headers += L"\r\n";

    if (!WinHttpSendRequest(request, headers.c_str(), static_cast<DWORD>(headers.size()),
                          const_cast<char*>(body.data()),
                          static_cast<DWORD>(body.size()),
                          static_cast<DWORD>(body.size()), 0)) {
        return std::nullopt;
    }
    if (!WinHttpReceiveResponse(request, nullptr)) return std::nullopt;

    DWORD status = 0;
    DWORD size = sizeof(status);
    WinHttpQueryHeaders(request,
                        WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &status, &size,
                        WINHTTP_NO_HEADER_INDEX);
    if (status < 200 || status >= 300) {
        if (err) *err = "bridge HTTP " + std::to_string(status);
        return std::nullopt;
    }

    std::string result;
    for (;;) {
        DWORD avail = 0;
        if (!WinHttpQueryDataAvailable(request, &avail)) return std::nullopt;
        if (avail == 0) break;
        std::string chunk(avail, '\0');
        DWORD read = 0;
        if (!WinHttpReadData(request, chunk.data(), avail, &read)) return std::nullopt;
        result.append(chunk, 0, read);
    }
    out = std::move(result);
    return out;
}

}  // namespace argos::bridge
