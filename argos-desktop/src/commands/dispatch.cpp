#include "commands/dispatch.h"

#include <map>
#include <string>

#include "core/app.h"
#include "core/config.h"
#include "core/log.h"
#include "tools/tools.h"

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

    if (method == "phone.ping")
        return {{"ok", true}, {"result", {{"pong", true}, {"name", hostname()}}}};

    if (method == "desktop.status") {
        a.ide().refresh();
        return {{"ok", true},
                {"result",
                 {{"name", hostname()},
                  {"robot_visible", a.robot().visible()},
                  {"ide_connected", a.ide().connected()},
                  {"ide", a.ide().connected() ? a.ide().endpoint().ide : ""},
                  {"workspace", a.ide().connected() ? a.ide().endpoint().workspace : ""},
                  {"tools", tools::schemas().size()}}}};
    }

    if (method == "tools.list")
        return {{"ok", true}, {"result", tools::schemas()}};

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

    // "task.prompt" — a free-text request. Until the Cerebras agent loop is
    // wired, the most useful behaviour is forwarding it into the IDE's AI
    // chatbox (the proven chat.send path) when an IDE bridge is live.
    if (method == "task.prompt") {
        std::string text = params.value("text", "");
        if (text.empty()) return {{"ok", false}, {"error", "text required"}};
        a.ide().refresh();
        if (!a.ide().connected())
            return {{"ok", false},
                    {"error", "no IDE connected — chatbox delivery unavailable"}};
        a.robot().set_state("thinking");
        auto r = a.ide().call("chat.send", {{"text", text}});
        if (!r)
            return {{"ok", false}, {"error", a.ide().last_error()}};
        return {{"ok", true}, {"result", *r}};
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
