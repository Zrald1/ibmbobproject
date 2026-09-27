#include "commands/dispatch.h"

#include <map>
#include <string>

#include "core/app.h"
#include "core/config.h"
#include "core/log.h"
#include "mcp/mcp_client.h"
#include "tools/tools.h"
#include "voice/voice.h"

namespace argos::commands {

using nlohmann::json;

namespace {

std::string hostname() {
    char buf[256]{};
    DWORD n = sizeof(buf);
    return GetComputerNameA(buf, &n) ? std::string(buf, n) : "Argos-PC";
}

}  // namespace

json dispatch(const std::string& method, const json& params) {
    auto& a = app();
    a.robot().notify_activity();  // any command counts as "in use" for roam gating

    if (method == "phone.ping")
        return {{"ok", true}, {"result", {{"pong", true}, {"name", hostname()}}}};

    if (method == "desktop.status") {
        a.ide().refresh();
        json ides = json::array();
        const auto& current = a.ide().endpoint();
        for (const auto& ep : bridge::IdeBridge::enumerate()) {
            ides.push_back({{"ide", ep.ide},
                            {"version", ep.version},
                            {"port", ep.port},
                            {"workspace", ep.workspace},
                            {"current", ep.pid == current.pid &&
                                            ep.port == current.port}});
        }
        return {{"ok", true},
                {"result",
                 {{"name", hostname()},
                  {"robot_visible", a.robot().visible()},
                  {"ide_connected", a.ide().connected()},
                  {"ide", a.ide().connected() ? a.ide().endpoint().ide : ""},
                  {"workspace", a.ide().connected() ? a.ide().endpoint().workspace : ""},
                  {"ides", ides},
                  {"tools", tools::schemas().size()}}}};
    }

    if (method == "tools.list")
        return {{"ok", true}, {"result", tools::schemas()}};

    // Per-server connection state for the Settings tab / phone clients.
    if (method == "mcp.status") {
        json arr = json::array();
        for (const auto& s : mcp::client().status())
            arr.push_back({{"name", s.name},
                           {"type", s.type},
                           {"connected", s.connected},
                           {"tools", s.tool_count},
                           {"error", s.error},
                           {"server", s.server_info}});
        return {{"ok", true},
                {"result", {{"connecting", mcp::client().connecting()},
                            {"servers", arr}}}};
    }

    // "mcp.reconnect" — re-run connect_all after the user edits the list.
    if (method == "mcp.reconnect") {
        mcp::client().connect_all();
        return {{"ok", true}, {"result", {{"reconnecting", true}}}};
    }

    // "chat.post" — surface an out-of-band message into the chatbox without
    // running an agent turn. This is how MCP-bridged CLI agents (Gemini CLI,
    // Claude Code, …) report results back to the user.
    if (method == "chat.post") {
        const std::string from = params.value("from", "terminal");
        const std::string text = params.value("text", "");
        if (text.empty()) return {{"ok", false}, {"error", "empty text"}};
        a.agent().post_visible("tool",
                               "[" + from + "] " + text);
        return {{"ok", true}, {"result", {{"posted", true}}}};
    }

    // Terminal task queue — pending work pasted into a terminal window is
    // claimable here by the agent running inside it (via the Argos MCP bridge).
    if (method == "terminal.task.next") {
        const DWORD pid = (DWORD)params.value("pid", 0);
        auto t = tools::terminal_task_claim(pid);
        if (t.is_null())
            return {{"ok", true}, {"result", {{"task", nullptr}}}};
        return {{"ok", true}, {"result", {{"task", t}}}};
    }
    if (method == "terminal.task.pending") {
        return {{"ok", true},
                {"result", {{"tasks", tools::terminal_task_pending()}}}};
    }
    if (method == "terminal.task.done") {
        const std::string id = params.value("id", "");
        const std::string result = params.value("result", "");
        auto t = tools::terminal_task_complete(id, result);
        return {{"ok", !t.is_null()}, {"result", t}};
    }

    // "voice.listen" — toggle mic listening (same as double-clicking the
    // robot). Returns the resulting listening state.
    if (method == "voice.listen") {
        voice::toggle_listen();
        return {{"ok", true}, {"result", {{"listening", voice::listening()}}}};
    }

    // Robot control — the phone can animate/drive the desktop robot.
    if (method.rfind("robot.", 0) == 0) {
        std::string what = method.substr(6);
        std::string value = params.value("name", params.value("value", ""));
        if (what == "state") a.robot().set_state(value);
        else if (what == "expression") a.robot().set_expression(value);
        else if (what == "gesture") a.robot().set_hand_gesture(value);
        else if (what == "thinking") a.robot().set_thinking(params.value("on", true));
        else if (what == "talking") a.robot().set_talking(params.value("on", true));
        else if (what == "listening") a.robot().set_listening(params.value("on", true));
        else if (what == "move")
            a.robot().move_to(params.value("x", 0), params.value("y", 0));
        else if (what == "visible") a.robot().show(params.value("on", true));
        else
            return {{"ok", false}, {"error", "unknown robot method " + what}};
        return {{"ok", true}, {"result", {{"robot", what}, {"value", value}}}};
    }

    // "task.prompt" — a free-text request. With a Cerebras key configured the
    // local agent loop handles it (it can run tools itself); otherwise it
    // falls back to forwarding into the IDE's AI chatbox when a bridge is live.
    if (method == "task.prompt") {
        std::string text = params.value("text", "");
        if (text.empty()) return {{"ok", false}, {"error", "text required"}};
        if (a.agent().ready()) {
            auto [ok, reply] = a.agent().ask_sync(text);
            return ok ? json{{"ok", true}, {"result", {{"reply", reply}}}}
                      : json{{"ok", false}, {"error", reply}};
        }
        a.ide().refresh();
        if (!a.ide().connected())
            return {{"ok", false},
                    {"error", "no Cerebras key and no IDE connected — "
                              "chatbox delivery unavailable"}};
        a.robot().set_state("thinking");
        auto r = a.ide().call("chat.send", {{"text", text}});
        if (!r)
            return {{"ok", false}, {"error", a.ide().last_error()}};
        return {{"ok", true}, {"result", *r}};
    }

    // MCP tools (mcp_<server>_<tool>) route straight to the attached server.
    if (method.rfind("mcp_", 0) == 0) {
        auto [ok, out] = mcp::client().call(method, params);
        return ok ? json{{"ok", true}, {"result", {{"output", out}}}}
                  : json{{"ok", false}, {"error", out}};
    }

    // Any other method is treated as a tool call. Accept both naming styles:
    // "read_file" (tool name) or "file.read" (bridge-style, reverse-mapped).
    std::string tool = method;
    if (method.find('.') != std::string::npos) {
        static const std::map<std::string, std::string> bridge_to_tool = {
            {"file.read", "read_file"},       {"file.write", "write_file"},
            {"file.apply_diff", "apply_diff"},{"file.insert", "insert_content"},
            {"file.list", "list_files"},      {"file.stat", "file_stat"},
            {"text.search", "search_files"},  {"file.rename", "rename_file"},
            {"file.copy", "copy_file"},       {"file.mkdir", "make_dir"},
            {"file.delete", "delete_file"},   {"terminal.run", "execute_command"},
            {"file.open", "open_in_editor"},  {"problems.get", "get_diagnostics"},
            {"symbols.list", "list_symbols"}, {"ide.status", "ide_status"},
            {"editor.save", "save_file"},     {"editor.saveAll", "save_all"},
        };
        auto it = bridge_to_tool.find(method);
        if (it == bridge_to_tool.end())
            return {{"ok", false}, {"error", "unknown method " + method}};
        tool = it->second;
    }

    auto res = tools::execute(tool, params);
    json out = {{"ok", res.ok}, {"result", {{"output", res.output}}}};
    if (!res.data.is_null()) out["result"]["data"] = res.data;
    if (!res.ok) out["error"] = res.output;
    return out;
}

}  // namespace argos::commands
