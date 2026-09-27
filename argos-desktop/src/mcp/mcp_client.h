#pragma once

// MCP (Model Context Protocol) client.
//
// Lets the user attach ANY MCP server to Argos. Each connected server's
// tools are advertised to the Cerebras brain as mcp_<server>_<tool> and
// routed back to the server over its own transport:
//
//   type="stdio" — spawn the command with pipes (npx server, python server,
//                  any MCP stdio binary). JSON-RPC 2.0, newline-delimited.
//   type="http"  — streamable HTTP endpoint (POST JSON-RPC, JSON or
//                  text/event-stream responses, Mcp-Session-Id tracked).
//
// Configuration lives in config().mcp.servers and is edited in the
// Settings tab; connect_all() runs on a worker thread because spawning /
// handshaking a server can take seconds.

#include <windows.h>

#include <atomic>
#include <condition_variable>
#include <map>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include <nlohmann/json.hpp>

#include "core/config.h"

namespace argos::mcp {

struct ServerStatus {
    std::string name;
    std::string type;
    bool connected = false;
    int tool_count = 0;
    std::string error;
    std::string server_info;  // "name version" from the initialize reply
};

class McpClient {
public:
    static McpClient& instance();

    // (Re)connect every enabled server on a background thread. Safe to call
    // again after the user edits the server list.
    void connect_all();
    void shutdown();

    // OpenAI-format tool schemas for every connected server's tools,
    // renamed mcp_<server>_<tool>.
    nlohmann::json tool_schemas();

    // Route an mcp_<server>_<tool> call. Returns the tool result text or an
    // error message.
    std::pair<bool, std::string> call(const std::string& prefixed_name,
                                      const nlohmann::json& arguments);

    std::vector<ServerStatus> status();
    bool connecting() const { return connecting_.load(); }

private:
    McpClient() = default;
    ~McpClient() { shutdown(); }

    struct Server;  // per-server state + transport impl

    std::vector<std::shared_ptr<Server>> servers_;
    std::mutex mu_;
    std::atomic<bool> connecting_{false};
    std::thread connect_thread_;
};

inline McpClient& client() { return McpClient::instance(); }

}  // namespace argos::mcp
