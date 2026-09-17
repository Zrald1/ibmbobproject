#pragma once

// Phone link server: lets the Android Argos app drive the desktop over LAN.
//
// Pairing flow (same pattern as Voicing / KDE Connect):
//   1. Desktop listens on 0.0.0.0:<port> and shows a QR code containing
//      {"v":1,"ips":[...],"port":P,"token":"T","name":"Argos-PC"}.
//   2. Phone scans it, saves the endpoint, and calls POST /command with
//      Authorization: Bearer <token>.
//
// The command envelope mirrors the IDE bridge — {method, params} in,
// {ok, result|error} out — so both transports share one protocol.

#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <nlohmann/json.hpp>

namespace argos::phone {

struct PeerEvent {
    std::string when;
    std::string ip;
    std::string method;
    bool ok;
};

class PhoneServer {
public:
    bool start();
    void stop();
    bool running() const { return running_; }
    int port() const { return bound_port_; }

    const std::string& token() const { return token_; }
    std::vector<std::string> lan_ips() const;
    std::string qr_payload() const;

    // Recent peer activity for the Phone tab.
    std::vector<PeerEvent> recent_events() const;

    static PhoneServer& instance();

private:
    void accept_loop();
    void handle_client(uintptr_t sock);
    nlohmann::json dispatch(const std::string& method, const nlohmann::json& params,
                            const std::string& peer_ip);
    void log_event(const std::string& ip, const std::string& method, bool ok);

    std::atomic<bool> running_{false};
    uintptr_t listen_sock_ = ~0ull;
    std::thread accept_thread_;
    int bound_port_ = 0;
    std::string token_;

    mutable std::mutex events_mu_;
    std::vector<PeerEvent> events_;
};

PhoneServer& phone_server();

}  // namespace argos::phone
