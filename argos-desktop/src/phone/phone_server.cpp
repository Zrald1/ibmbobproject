#include "phone/phone_server.h"

#include <winsock2.h>
#include <ws2tcpip.h>
#include <iphlpapi.h>
#include <bcrypt.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <map>
#include <sstream>

#include "commands/dispatch.h"
#include "core/config.h"
#include "core/log.h"
#include "platform/win_util.h"

namespace argos::phone {
namespace {

using nlohmann::json;

constexpr size_t kMaxRequestBytes = 1 << 20;  // 1 MB cap on a phone request
constexpr size_t kMaxEvents = 50;

std::string now_stamp() {
    char buf[32];
    SYSTEMTIME st{};
    GetLocalTime(&st);
    snprintf(buf, sizeof(buf), "%02u:%02u:%02u", st.wHour, st.wMinute, st.wSecond);
    return buf;
}

std::string lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(),
                   [](unsigned char c) { return (char)std::tolower(c); });
    return s;
}

std::string hostname() {
    char buf[256]{};
    DWORD n = sizeof(buf);
    return GetComputerNameA(buf, &n) ? std::string(buf, n) : "Argos-PC";
}

// 256-bit pairing secret, hex-encoded. Generated once and persisted
// (DPAPI-protected) in config.json — like KDE Connect's paired device model.
// Rotating it via the Phone tab revokes every paired phone.
std::string make_token() {
    unsigned char bytes[32];
    if (BCryptGenRandom(nullptr, bytes, sizeof(bytes),
                        BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0)
        return {};
    static const char* hex = "0123456789abcdef";
    std::string out;
    out.reserve(sizeof(bytes) * 2);
    for (auto b : bytes) {
        out.push_back(hex[b >> 4]);
        out.push_back(hex[b & 0xF]);
    }
    return out;
}

// ── Minimal HTTP/1.1 plumbing ─────────────────────────────────────────────

struct Request {
    std::string method;
    std::string path;
    std::map<std::string, std::string> headers;  // lower-cased keys
    std::string body;
};

bool send_all(uintptr_t s, const char* data, int len) {
    int sent = 0;
    while (sent < len) {
        int n = send((SOCKET)s, data + sent, len - sent, 0);
        if (n <= 0) return false;
        sent += n;
    }
    return true;
}

bool send_all(uintptr_t s, const std::string& str) {
    return send_all(s, str.data(), (int)str.size());
}

// Reads one request: header block, then Content-Length body.
// Returns false on EOF/garbage. Writes the raw header so keep-alive framing
// stays simple (we always close anyway).
bool read_request(uintptr_t s, Request& req) {
    std::string raw;
    raw.reserve(8192);
    char buf[8192];

    // Read until end-of-headers.
    size_t hdr_end = std::string::npos;
    while (hdr_end == std::string::npos) {
        if (raw.size() >= kMaxRequestBytes) return false;
        int n = recv((SOCKET)s, buf, sizeof(buf), 0);
        if (n <= 0) return false;
        raw.append(buf, n);
        hdr_end = raw.find("\r\n\r\n");
    }

    std::string head = raw.substr(0, hdr_end);
    std::string body = raw.substr(hdr_end + 4);

    std::istringstream lines(head);
    std::string line;
    if (!std::getline(lines, line)) return false;
    if (!line.empty() && line.back() == '\r') line.pop_back();
    {
        std::istringstream rl(line);
        std::string ver;
        if (!(rl >> req.method >> req.path >> ver)) return false;
    }
    while (std::getline(lines, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty()) continue;
        auto colon = line.find(':');
        if (colon == std::string::npos) continue;
        std::string key = lower(line.substr(0, colon));
        std::string val = line.substr(colon + 1);
        val.erase(0, val.find_first_not_of(" \t"));
        req.headers[key] = val;
    }

    // Honour Expect: 100-continue (HttpURLConnection sends it for posts).
    if (lower(req.headers["expect"]) == "100-continue")
        send_all(s, "HTTP/1.1 100 Continue\r\n\r\n");

    size_t want = 0;
    if (auto it = req.headers.find("content-length"); it != req.headers.end())
        want = (size_t)strtoull(it->second.c_str(), nullptr, 10);
    if (want > kMaxRequestBytes) return false;

    while (body.size() < want) {
        int n = recv((SOCKET)s, buf, sizeof(buf), 0);
        if (n <= 0) return false;
        body.append(buf, n);
    }
    req.body = body.substr(0, want);
    return true;
}

void respond(uintptr_t s, int status, const std::string& status_text,
             const json& payload) {
    std::string body = payload.dump();
    std::string res = "HTTP/1.1 " + std::to_string(status) + " " + status_text +
                      "\r\nContent-Type: application/json\r\nContent-Length: " +
                      std::to_string(body.size()) +
                      "\r\nConnection: close\r\n\r\n" + body;
    send_all(s, res);
}

// ── LAN address enumeration ───────────────────────────────────────────────
// Collects usable IPv4 LAN addresses, Wi-Fi first (the phone is almost
// always on Wi-Fi), then Ethernet, skipping virtual/VPN/loopback adapters.

bool is_virtual_adapter(const IP_ADAPTER_ADDRESSES* a) {
    std::string blob;
    if (a->FriendlyName) blob += win::to_utf8(a->FriendlyName);
    if (a->Description) blob += win::to_utf8(a->Description);
    for (auto& c : blob) c = (char)std::tolower((unsigned char)c);
    static const char* bad[] = {
        "virtual", "vmware", "hyper-v", "vethernet", "wsl",   "bluetooth",
        "tap-",    "tailscale", "wireguard", "zerotier", "loopback", "tunnel",
        "vpn",     "isatap",  "teredo",      "6to4",    "wan miniport",
    };
    for (auto* b : bad)
        if (blob.find(b) != std::string::npos) return true;
    return false;
}

struct Addr {
    std::string ip;
    int rank;  // lower sorts first
};

}  // namespace

PhoneServer& phone_server() { return PhoneServer::instance(); }

PhoneServer& PhoneServer::instance() {
    static PhoneServer inst;
    return inst;
}

std::vector<std::string> PhoneServer::lan_ips() const {
    std::vector<Addr> found;
    ULONG len = 0;
    GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                                      GAA_FLAG_SKIP_DNS_SERVER,
                         nullptr, nullptr, &len);
    if (len == 0) return {};
    std::vector<unsigned char> buf(len);
    auto* addrs = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buf.data());
    if (GetAdaptersAddresses(AF_INET,
                             GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                                 GAA_FLAG_SKIP_DNS_SERVER,
                             nullptr, addrs, &len) != NO_ERROR)
        return {};

    for (auto* a = addrs; a; a = a->Next) {
        if (a->OperStatus != IfOperStatusUp) continue;
        if (a->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
        if (is_virtual_adapter(a)) continue;
        int rank = a->IfType == IF_TYPE_IEEE80211 ? 0
                   : a->IfType == IF_TYPE_ETHERNET_CSMACD ? 1
                                                        : 2;
        for (auto* u = a->FirstUnicastAddress; u; u = u->Next) {
            auto* sa = reinterpret_cast<sockaddr_in*>(u->Address.lpSockaddr);
            char ip[INET_ADDRSTRLEN]{};
            inet_ntop(AF_INET, &sa->sin_addr, ip, sizeof(ip));
            std::string s = ip;
            if (s.rfind("169.254.", 0) == 0) continue;  // link-local is useless
            found.push_back({s, rank});
        }
    }
    std::stable_sort(found.begin(), found.end(),
                     [](const Addr& a, const Addr& b) { return a.rank < b.rank; });
    std::vector<std::string> out;
    for (auto& a : found) out.push_back(a.ip);
    return out;
}

std::string PhoneServer::qr_payload() const {
    json p;
    p["v"] = 1;
    p["app"] = "argos";
    p["name"] = hostname();
    p["port"] = bound_port_;
    p["token"] = token_;
    p["ips"] = lan_ips();
    return p.dump();
}

bool PhoneServer::start() {
    if (running_) return true;

    token_ = config().phone.token;
    if (token_.empty()) {
        token_ = make_token();
        if (token_.empty()) {
            log::error("phone: could not generate pairing token");
            return false;
        }
        config().phone.token = token_;
        config_save();
        log::info("phone: generated new pairing token");
    }

    WSADATA wsa{};
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        log::error("phone: WSAStartup failed");
        return false;
    }

    SOCKET s = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (s == INVALID_SOCKET) {
        log::error("phone: socket() failed");
        WSACleanup();
        return false;
    }
    BOOL yes = TRUE;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, (const char*)&yes, sizeof(yes));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    int try_port = config().phone.port;
    for (int attempt = 0; attempt < 2; ++attempt) {
        addr.sin_port = htons((u_short)try_port);
        if (bind(s, (sockaddr*)&addr, sizeof(addr)) == 0) break;
        if (attempt == 0) try_port = 0;  // fall back to an ephemeral port
        else {
            log::error("phone: bind failed — could not open a listening port");
            closesocket(s);
            WSACleanup();
            return false;
        }
    }
    sockaddr_in bound{};
    int blen = sizeof(bound);
    getsockname(s, (sockaddr*)&bound, &blen);
    bound_port_ = ntohs(bound.sin_port);

    if (listen(s, 8) != 0) {
        log::error("phone: listen() failed");
        closesocket(s);
        WSACleanup();
        return false;
    }

    listen_sock_ = (uintptr_t)s;
    running_ = true;
    accept_thread_ = std::thread(&PhoneServer::accept_loop, this);
    log::info("phone: listening on 0.0.0.0:" + std::to_string(bound_port_));
    for (auto& ip : lan_ips()) log::info("phone:   LAN address " + ip);
    return true;
}

void PhoneServer::stop() {
    if (!running_) return;
    running_ = false;
    if (listen_sock_ != ~0ull) {
        shutdown((SOCKET)listen_sock_, SD_BOTH);
        closesocket((SOCKET)listen_sock_);
        listen_sock_ = ~0ull;
    }
    if (accept_thread_.joinable()) accept_thread_.join();
    WSACleanup();
    log::info("phone: server stopped");
}

void PhoneServer::accept_loop() {
    while (running_) {
        sockaddr_in peer{};
        int plen = sizeof(peer);
        SOCKET c = accept((SOCKET)listen_sock_, (sockaddr*)&peer, &plen);
        if (c == INVALID_SOCKET) break;  // listener closed
        DWORD timeout = 15000;           // phones stall; don't leak threads
        setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, (const char*)&timeout, sizeof(timeout));
        std::thread(&PhoneServer::handle_client, this, (uintptr_t)c).detach();
    }
}

void PhoneServer::handle_client(uintptr_t sock) {
    SOCKET s = (SOCKET)sock;
    Request req;
    if (!read_request(sock, req)) {
        closesocket(s);
        return;
    }

    // Who is calling (for the activity log and per-peer display).
    // NB: peer address isn't part of Request — log best-effort via getsockname
    // alternative: stored when accept() ran is lost; recover via getpeername.
    sockaddr_in pa{};
    int plen = sizeof(pa);
    std::string peer_ip = "?";
    if (getpeername(s, (sockaddr*)&pa, &plen) == 0) {
        char ip[INET_ADDRSTRLEN]{};
        inet_ntop(AF_INET, &pa.sin_addr, ip, sizeof(ip));
        peer_ip = ip;
    }

    if (req.method == "GET" && req.path == "/health") {
        respond(sock, 200, "OK",
                {{"ok", true},
                 {"service", "argos-desktop"},
                 {"name", hostname()},
                 {"port", bound_port_},
                 {"paired", !token_.empty()}});
        log_event(peer_ip, "GET /health", true);
        closesocket(s);
        return;
    }

    if (req.method == "POST" && req.path == "/command") {
        // Bearer auth — same shape as the IDE bridge.
        std::string auth = req.headers["authorization"];
        bool authed = !token_.empty() && auth == "Bearer " + token_;
        if (!authed) {
            respond(sock, 401, "Unauthorized",
                    {{"ok", false}, {"error", "unauthorized"}});
            log_event(peer_ip, "auth failed", false);
            closesocket(s);
            return;
        }
        json body = json::parse(req.body, nullptr, false);
        if (body.is_discarded() || !body.contains("method")) {
            respond(sock, 400, "Bad Request",
                    {{"ok", false}, {"error", "expected {method, params}"}});
            log_event(peer_ip, "bad request", false);
            closesocket(s);
            return;
        }
        std::string method = body.value("method", "");
        json params = body.value("params", json::object());
        json result = dispatch(method, params, peer_ip);
        bool ok = result.value("ok", false);
        respond(sock, ok ? 200 : 400, ok ? "OK" : "Bad Request", result);
        log_event(peer_ip, method, ok);
        closesocket(s);
        return;
    }

    respond(sock, 404, "Not Found", {{"ok", false}, {"error", "not found"}});
    log_event(peer_ip, req.method + " " + req.path, false);
    closesocket(s);
}

json PhoneServer::dispatch(const std::string& method, const json& params,
                           const std::string& peer_ip) {
    (void)peer_ip;
    return commands::dispatch(method, params);
}

std::vector<PeerEvent> PhoneServer::recent_events() const {
    std::lock_guard<std::mutex> lk(events_mu_);
    return events_;  // newest last; UI renders reversed
}

void PhoneServer::log_event(const std::string& ip, const std::string& method,
                            bool ok) {
    std::lock_guard<std::mutex> lk(events_mu_);
    events_.push_back({now_stamp(), ip, method, ok});
    if (events_.size() > kMaxEvents) events_.erase(events_.begin());
    log::info("phone: " + ip + " " + method + (ok ? " ok" : " FAILED"));
}

}  // namespace argos::phone
