#pragma once

// Client for the Argos IDE bridge extension (extension-ide/).
//
// The extension runs an HTTP server on 127.0.0.1 inside VS Code, Cursor,
// Windsurf or VSCodium and drops a discovery file at
// %APPDATA%\ArgosDesktop\ide-bridge.json. This class reads that file and posts
// JSON commands, so the rest of the app can drive the editor and its AI chat
// assistants without knowing any of the transport details.

#include <optional>
#include <string>
#include <string_view>

#include <nlohmann/json.hpp>

namespace argos::bridge {

struct BridgeEndpoint {
    int port = 0;
    std::string token;
    std::string ide;
    std::string workspace;
    int pid = 0;       // extension host
    int main_pid = 0;  // IDE process that owns the window (fallback: pid)
};

class IdeBridge {
public:
    // Reads the discovery file and probes /health. Returns true when a bridge
    // answered — cheap enough to call every few seconds from the UI.
    bool refresh();

    bool connected() const { return connected_; }
    const BridgeEndpoint& endpoint() const { return endpoint_; }

    // POST /command. Returns the parsed `result` object on success.
    // On transport failure sets connected_ = false and returns nullopt;
    // on a remote error returns nullopt (check last_error()).
    std::optional<nlohmann::json> call(std::string_view method,
                                       const nlohmann::json& params = nlohmann::json::object());

    const std::string& last_error() const { return last_error_; }

private:
    std::optional<std::string> http_post(std::string_view path, std::string_view body);

    BridgeEndpoint endpoint_;
    bool connected_ = false;
    std::string last_error_;
};

}  // namespace argos::bridge
