#include "link/link_client.h"

#include <windows.h>
#include <winhttp.h>

#include <chrono>
#include <fstream>

#include "commands/dispatch.h"
#include "core/config.h"
#include "core/log.h"
#include "platform/win_util.h"

namespace argos::link {
namespace {

constexpr int kTimeoutMs = 10000;
constexpr int kPollMs = 2000;

std::string hostname() {
    char buf[256]{};
    DWORD n = sizeof(buf);
    return GetComputerNameA(buf, &n) ? std::string(buf, n) : "Argos-PC";
}

}  // namespace

LinkClient& link_client() { return LinkClient::instance(); }

LinkClient& LinkClient::instance() {
    static LinkClient inst;
    return inst;
}

bool LinkClient::start() {
    if (running_) return true;
    if (config().link.base_url.empty()) {
        log::info("link: no backend_url configured — backend relay off");
        return false;
    }
    {
        std::lock_guard<std::mutex> lk(mu_);
        desktop_id_ = config().link.desktop_id;
        desktop_token_ = config().link.desktop_token;
    }
    running_ = true;
    worker_ = std::thread(&LinkClient::run, this);
    log::info("link: relay client started (backend " + config().link.base_url + ")");
    return true;
}

void LinkClient::stop() {
    if (!running_) return;
    running_ = false;
    if (worker_.joinable()) worker_.join();
    registered_ = false;
    std::error_code ec;
    std::filesystem::remove(win::app_data_dir() / "link-qr.json", ec);
    log::info("link: relay client stopped");
}

void LinkClient::run() {
    while (running_) {
        if (!registered_) {
            if (!ensure_registered()) {
                for (int i = 0; i < 30 && running_; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(100));
                continue;  // backend unreachable — retry every ~3s
            }
        }

        // Re-register when the pair code is about to expire so the QR on
        // screen stays scannable while the Phone tab is open.
        {
            std::lock_guard<std::mutex> lk(mu_);
            const auto now = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
            if (pair_expires_ > 0 && now > pair_expires_ - 30) {
                registered_ = false;
                continue;  // re-register → fresh pair code
            }
        }

        poll_once();
        for (int i = 0; i < kPollMs / 50 && running_; ++i)
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
}

bool LinkClient::ensure_registered() {
    nlohmann::json body = {{"name", hostname()}};
    {
        std::lock_guard<std::mutex> lk(mu_);
        if (!desktop_id_.empty() && !desktop_token_.empty()) {
            body["desktop_id"] = desktop_id_;
            body["desktop_token"] = desktop_token_;
        }
    }

    auto r = post("/api/link/register", body);
    if (!r || !r->value("ok", false)) {
        std::lock_guard<std::mutex> lk(mu_);
        last_error_ = "register failed";
        return false;
    }

    {
        std::lock_guard<std::mutex> lk(mu_);
        desktop_id_ = r->value("desktop_id", "");
        desktop_token_ = r->value("desktop_token", "");
        pair_code_ = r->value("pair_code", "");
        pair_expires_ = r->value("pair_expires", 0LL);
        bool reused = r->value("reused", false);
        registered_ = true;
        last_error_.clear();

        // Persist the desktop identity so restarts keep the same pairing.
        if (config().link.desktop_id != desktop_id_ ||
            config().link.desktop_token != desktop_token_) {
            config().link.desktop_id = desktop_id_;
            config().link.desktop_token = desktop_token_;
            config_save();
        }
        log::info("link: registered as " + desktop_id_ + (reused ? " (reused identity)" : ""));
    }

    // Mirror the current pairing payload next to config.json — same secrecy
    // as the on-screen QR, and lets local tooling/tests pair headlessly.
    {
        std::ofstream f(win::app_data_dir() / "link-qr.json",
                        std::ios::binary | std::ios::trunc);
        if (f.is_open()) f << qr_payload();
    }
    return true;
}

void LinkClient::poll_once() {
    std::string id, token;
    {
        std::lock_guard<std::mutex> lk(mu_);
        id = desktop_id_;
        token = desktop_token_;
    }
    auto r = get("/api/link/poll?desktop_id=" + id + "&token=" + token);
    if (!r) {
        std::lock_guard<std::mutex> lk(mu_);
        last_error_ = "poll failed";
        // Persistent failure usually means the backend restarted and forgot
        // our registration — after ~10s of failures, re-register.
        if (++poll_failures_ >= 5) {
            log::warn("link: polls failing — re-registering");
            registered_ = false;
            poll_failures_ = 0;
        }
        return;
    }
    if (!r->value("ok", false)) {
        // Backend restarted and forgot us — re-register.
        std::string detail = r->value("detail", "");
        {
            std::lock_guard<std::mutex> lk(mu_);
            last_error_ = detail.empty() ? "poll rejected" : detail;
        }
        if (detail.find("unknown desktop") != std::string::npos) {
            log::warn("link: backend lost our registration — re-registering");
            registered_ = false;
        }
        return;
    }
    {
        std::lock_guard<std::mutex> lk(mu_);
        last_error_.clear();
        poll_failures_ = 0;
    }

    for (const auto& cmd : r->value("commands", nlohmann::json::array())) {
        std::string cid = cmd.value("id", "");
        std::string method = cmd.value("method", "");
        nlohmann::json params = cmd.value("params", nlohmann::json::object());
        log::info("link: command " + cid + " " + method);
        execute_and_report(cid, method, params);
    }
}

void LinkClient::execute_and_report(const std::string& id, const std::string& method,
                                    const nlohmann::json& params) {
    nlohmann::json result = commands::dispatch(method, params);
    bool ok = result.value("ok", false);

    std::string did, token;
    {
        std::lock_guard<std::mutex> lk(mu_);
        did = desktop_id_;
        token = desktop_token_;
        ++commands_run_;
    }
    nlohmann::json body = {{"desktop_id", did},
                           {"token", token},
                           {"command_id", id},
                           {"ok", ok}};
    if (ok) body["result"] = result.value("result", nlohmann::json::object());
    else body["error"] = result.value("error", "failed");
    post("/api/link/result", body);
}

std::string LinkClient::qr_payload() const {
    std::lock_guard<std::mutex> lk(mu_);
    if (!registered_) return {};
    nlohmann::json p;
    p["v"] = 2;
    p["app"] = "argos";
    p["url"] = config().link.base_url;
    p["fallback"] = config().link.fallback_url;
    p["desktop"] = desktop_id_;
    p["pair"] = pair_code_;
    return p.dump();
}

std::string LinkClient::status_line() const {
    std::lock_guard<std::mutex> lk(mu_);
    if (!running_) return "off";
    if (!registered_)
        return last_error_.empty() ? "connecting…" : "connecting… (" + last_error_ + ")";
    std::string s = desktop_id_ + " via " + (active_base_.empty() ? "backend" : active_base_);
    if (commands_run_ > 0) s += " · " + std::to_string(commands_run_) + " commands run";
    return s;
}

bool LinkClient::revoke_phones() {
    std::string id, token;
    {
        std::lock_guard<std::mutex> lk(mu_);
        id = desktop_id_;
        token = desktop_token_;
    }
    auto r = post("/api/link/revoke", {{"desktop_id", id}, {"token", token}});
    if (!r || !r->value("ok", false)) return false;
    std::lock_guard<std::mutex> lk(mu_);
    pair_code_ = r->value("pair_code", pair_code_);
    pair_expires_ = r->value("pair_expires", pair_expires_);
    return true;
}

// ── HTTP plumbing ─────────────────────────────────────────────────────────

std::optional<nlohmann::json> LinkClient::post(const std::string& path,
                                                 const nlohmann::json& body) {
    const std::string b = body.dump();
    for (const std::string* base : {&config().link.base_url, &config().link.fallback_url}) {
        if (base->empty()) continue;
        if (auto r = request(*base, L"POST", path, &b)) {
            try {
                auto j = nlohmann::json::parse(*r);
                std::lock_guard<std::mutex> lk(mu_);
                active_base_ = *base;
                return j;
            } catch (...) {
                // fall through to fallback
            }
        }
    }
    return std::nullopt;
}

std::optional<nlohmann::json> LinkClient::get(const std::string& path) {
    for (const std::string* base : {&config().link.base_url, &config().link.fallback_url}) {
        if (base->empty()) continue;
        if (auto r = request(*base, L"GET", path, nullptr)) {
            try {
                auto j = nlohmann::json::parse(*r);
                std::lock_guard<std::mutex> lk(mu_);
                active_base_ = *base;
                return j;
            } catch (...) {
            }
        }
    }
    return std::nullopt;
}

// One HTTP request against a base URL like "http://host:8080/java".
std::optional<std::string> LinkClient::request(const std::string& base,
                                                 const std::wstring& verb,
                                                 const std::string& path,
                                                 const std::string* body) {
    URL_COMPONENTSW uc{};
    uc.dwStructSize = sizeof(uc);
    wchar_t host[256]{}, urlpath[1024]{}, scheme[16]{};
    uc.lpszHostName = host;
    uc.dwHostNameLength = 256;
    uc.lpszUrlPath = urlpath;
    uc.dwUrlPathLength = 1024;
    uc.lpszScheme = scheme;
    uc.dwSchemeLength = 16;

    std::wstring wbase = win::to_wide(base);
    if (!WinHttpCrackUrl(wbase.c_str(), 0, 0, &uc)) return std::nullopt;

    const bool https = uc.nScheme == INTERNET_SCHEME_HTTPS;
    std::wstring full_path = std::wstring(urlpath, uc.dwUrlPathLength) + win::to_wide(path);

    HINTERNET session =
        WinHttpOpen(L"ArgosDesktop/0.1", WINHTTP_ACCESS_TYPE_NO_PROXY,
                    WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) return std::nullopt;

    HINTERNET connection = nullptr, req = nullptr;
    std::optional<std::string> out;
    struct Guard {
        HINTERNET& a; HINTERNET& b; HINTERNET& c;
        ~Guard() {
            if (c) WinHttpCloseHandle(c);
            if (b) WinHttpCloseHandle(b);
            if (a) WinHttpCloseHandle(a);
        }
    } guard{session, connection, req};

    WinHttpSetTimeouts(session, kTimeoutMs, kTimeoutMs, kTimeoutMs, kTimeoutMs);
    connection = WinHttpConnect(session, host, uc.nPort, 0);
    if (!connection) return std::nullopt;

    req = WinHttpOpenRequest(connection, verb.c_str(), full_path.c_str(), nullptr,
                             WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                             https ? WINHTTP_FLAG_SECURE : 0);
    if (!req) return std::nullopt;

    static const wchar_t kHdr[] = L"Content-Type: application/json\r\n";
    if (!WinHttpSendRequest(req, kHdr, (DWORD)wcslen(kHdr),
                          body ? const_cast<char*>(body->data()) : nullptr,
                          body ? (DWORD)body->size() : 0,
                          body ? (DWORD)body->size() : 0, 0))
        return std::nullopt;
    if (!WinHttpReceiveResponse(req, nullptr)) return std::nullopt;

    DWORD status = 0, size = sizeof(status);
    WinHttpQueryHeaders(req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &status, &size,
                        WINHTTP_NO_HEADER_INDEX);
    if (status < 200 || status >= 300) return std::nullopt;

    std::string result;
    for (;;) {
        DWORD avail = 0;
        if (!WinHttpQueryDataAvailable(req, &avail)) return std::nullopt;
        if (avail == 0) break;
        std::string chunk(avail, '\0');
        DWORD read = 0;
        if (!WinHttpReadData(req, chunk.data(), avail, &read)) return std::nullopt;
        result.append(chunk, 0, read);
    }
    out = std::move(result);
    return out;
}

}  // namespace argos::link
