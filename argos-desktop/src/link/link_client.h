#pragma once

// Backend link client — the desktop side of the phone relay.
//
// The desktop registers with the Argos backend (POST /api/link/register),
// shows a QR containing {backend_url, desktop_id, pair_code}, and then
// polls for commands (GET /api/link/poll). The Android app scans the QR,
// pairs (POST /api/link/pair) and queues commands (POST /api/link/command);
// each executed command's outcome is posted back (POST /api/link/result)
// for the phone to read via /api/link/status.
//
// Polling (not WebSocket) is deliberate: both existing backends are plain
// REST, and an outbound poll needs no inbound ports or firewall rules.
//
// Two base URLs are supported — the primary Python backend and the Java
// fallback (mounted under /java on the same host). Every call tries the
// primary first and falls back automatically, matching the Android app's
// backend_url / fallback_backend_url pattern.

#include <atomic>
#include <mutex>
#include <optional>
#include <string>
#include <thread>

#include <nlohmann/json.hpp>

namespace argos::link {

class LinkClient {
public:
    bool start();   // begins register + poll loop on a worker thread
    void stop();
    bool running() const { return running_; }
    bool registered() const { return registered_; }

    // The pairing payload the Android app scans:
    // {"v":2,"app":"argos","url":...,"fallback":...,"desktop":"D-..","pair":".."}
    std::string qr_payload() const;

    // Human-readable status for the Phone tab.
    std::string status_line() const;

    // Revoke all paired phones and rotate the pair code (QR updates).
    bool revoke_phones();

    static LinkClient& instance();

private:
    void run();
    bool ensure_registered();
    void poll_once();
    void execute_and_report(const std::string& id, const std::string& method,
                            const nlohmann::json& params);

    // Tries the primary base URL, then the fallback. Returns parsed JSON.
    std::optional<nlohmann::json> post(const std::string& path,
                                       const nlohmann::json& body);
    std::optional<nlohmann::json> get(const std::string& path);

    std::optional<std::string> request(const std::string& base,
                                       const std::wstring& verb,
                                       const std::string& path,
                                       const std::string* body);

    std::atomic<bool> running_{false};
    std::atomic<bool> registered_{false};
    std::thread worker_;

    mutable std::mutex mu_;
    std::string desktop_id_;
    std::string desktop_token_;
    std::string pair_code_;
    long long pair_expires_ = 0;
    std::string active_base_;     // which backend answered last
    std::string last_error_;
    long long commands_run_ = 0;
    int poll_failures_ = 0;
};

LinkClient& link_client();

}  // namespace argos::link
