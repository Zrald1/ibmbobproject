#include "argos_tools_core.h"
#include "platform.h"
#include "ui_inspector.h"
#include <sstream>
#include <vector>
#include <fstream>
#include <sys/stat.h>
#include <cstdio>
#include <cstring>
#include <chrono>
#include <thread>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Argos", __VA_ARGS__)
#else
#define LOGI(...)
#endif

namespace argos_tools {

static std::string getMemoryFilePath() {
    return argos::getAppDataDir() + "/conversation_memory.jsonl";
}

bool rag_memory_save_conversation(const std::string& role, const std::string& content) {
    std::string path = getMemoryFilePath();
    // Ensure directory exists
    mkdir(argos::getAppDataDir().c_str(), 0777);

    FILE* f = fopen(path.c_str(), "a");
    if (!f) return false;

    std::string escaped;
    for (char c : content) {
        if (c == '"') escaped += "\\\"";
        else if (c == '\\') escaped += "\\\\";
        else if (c == '\n') escaped += "\\n";
        else if (c == '\r') escaped += "\\r";
        else if (c == '\t') escaped += "\\t";
        else escaped += c;
    }

    fprintf(f, "{\"role\":\"%s\",\"content\":\"%s\"}\n", role.c_str(), escaped.c_str());
    fclose(f);
    return true;
}

std::string rag_memory_load_conversation(size_t max_messages) {
    std::string path = getMemoryFilePath();
    FILE* f = fopen(path.c_str(), "r");
    if (!f) return "[]";

    std::vector<std::string> lines;
    char buffer[8192];
    while (fgets(buffer, sizeof(buffer), f)) {
        lines.push_back(std::string(buffer));
    }
    fclose(f);

    size_t start = (lines.size() > max_messages) ? lines.size() - max_messages : 0;
    std::string result = "[\n";
    for (size_t i = start; i < lines.size(); i++) {
        result += "  " + lines[i];
        if (i < lines.size() - 1) result += ",";
        result += "\n";
    }
    result += "]";
    return result;
}

// Tool implementations
// NOTE: list_files, read_file, and write_file tools have been REMOVED for security.
// File operations are now handled exclusively by the Android ArgosFileManager class,
// which enforces a sandboxed Argus folder. The AI can only access files within
// Documents/Argos/ via the [TOOL:WRITE_FILE], [TOOL:READ_FILE], [TOOL:LIST_FILES],
// [TOOL:EDIT_FILE], etc. tags, which are processed by FloatingRobotService.executeTool().
// This prevents the AI from reading or modifying arbitrary files on the device.

static std::string tool_cmd(const std::string& command) {
    FILE* pipe = popen(command.c_str(), "r");
    if (!pipe) return "{\"error\":\"Command failed\"}";
    std::string output;
    char buf[4096];
    while (fgets(buf, sizeof(buf), pipe)) {
        output += buf;
    }
    pclose(pipe);
    if (output.empty()) return "Command executed (no output).";
    if (output.size() > 3000) output = output.substr(0, 3000) + "...(truncated)";
    return output;
}

static std::string tool_recall() {
    return rag_memory_load_conversation(10);
}

static std::string tool_forget() {
    std::string path = getMemoryFilePath();
    if (remove(path.c_str()) == 0) return "{\"status\":\"Memory cleared\"}";
    return "{\"status\":\"No memory to clear\"}";
}

std::string dispatch_tool(const std::string& tool_name, const std::string& args) {
    std::string name = tool_name;
    // Lowercase
    for (auto& c : name) c = tolower(c);

    // NOTE: "list_files", "read", and "write" tools have been REMOVED for security.
    // File operations are handled by Android ArgosFileManager (sandboxed Argus folder).
    // The AI uses [TOOL:WRITE_FILE], [TOOL:READ_FILE], [TOOL:LIST_FILES], etc.
    // which are processed by FloatingRobotService.executeTool() in Java.
    if (name == "list_files" || name == "dir" || name == "ls" ||
        name == "read" || name == "write" || name == "write_file") {
        return "{\"error\":\"Direct file access is disabled. Use the sandboxed Argus file tools: WRITE_FILE, READ_FILE, LIST_FILES, EDIT_FILE, etc. These are handled by the Android layer.\"}";
    }
    if (name == "cmd" || name == "command" || name == "shell") {
        return tool_cmd(args);
    }
    if (name == "recall") {
        return tool_recall();
    }
    if (name == "forget") {
        return tool_forget();
    }
    // ── System tools (matching desktop) ──
    if (name == "open" || name == "open_path" || name == "open_file" || name == "open_folder") {
        return argos::openFile(args);
    }
    if (name == "run" || name == "run_app" || name == "launch") {
        return argos::runApp(args);
    }
    if (name == "clipboard" || name == "clip" || name == "copy") {
        return argos::clipboardCopy(args);
    }
    if (name == "volume" || name == "set_volume") {
        int level = 50;
        if (!args.empty()) {
            level = std::atoi(args.c_str());
            if (level < 0) level = 0;
            if (level > 100) level = 100;
        }
        return argos::setVolume(level);
    }
    if (name == "notify" || name == "notification") {
        if (args.empty()) return "{\"error\":\"notify needs: <message>\"}";
        return argos::showNotification(args);
    }
    if (name == "search") {
        // On mobile, open browser with Google search
        std::string url = "https://www.google.com/search?q=" + args;
        std::string result = argos::openUrl(url);
        std::this_thread::sleep_for(std::chrono::seconds(2));
        return result + "\nUse [TOOL:screen_text] to read the search results.";
    }
    if (name == "rag_search" || name == "search_files" || name == "search_filename") {
        return "{\"error\":\"RAG search not available on this platform\"}";
    }
    // ── Browser / Screen tools (Android Accessibility Service) ──
    if (name == "open_url" || name == "browser_open" || name == "navigate") {
        std::string result = argos::openUrl(args);
        // Wait for browser to load
        std::this_thread::sleep_for(std::chrono::seconds(2));
        return result;
    }
    if (name == "screen_text" || name == "read_screen" || name == "browser_content") {
        return argos::getScreenText();
    }
    if (name == "screen_active" || name == "active_app") {
        return argos::getActiveApp();
    }
    if (name == "click_text" || name == "browser_click") {
        std::string result = argos::clickText(args);
        // Wait for screen to update after click
        std::this_thread::sleep_for(std::chrono::seconds(1));
        return result;
    }
    if (name == "type_text" || name == "browser_type") {
        return argos::typeText(args);
    }
    if (name == "scroll") {
        int dir = 1; // default down
        if (args == "up" || args == "0") dir = 0;
        std::string result = argos::scrollScreen(dir);
        // Wait for scroll to settle
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        return result;
    }

    // ── UI Inspection & Automation tools ──

    if (name == "ui_inspect" || name == "inspect_ui" || name == "ui_tree") {
        // Optional depth argument: "ui_inspect" or "ui_inspect 15"
        int maxDepth = -1;
        if (!args.empty()) {
            maxDepth = std::atoi(args.c_str());
            if (maxDepth <= 0) maxDepth = -1;
        }
        return argos::getUITree(maxDepth);
    }

    if (name == "ui_click" || name == "ui_tap") {
        // Args can be: element ID number, or text to find
        if (args.empty()) return "{\"error\":\"ui_click needs element ID or text\"}";
        // Check if args is a number (element ID)
        bool isNumber = true;
        for (char c : args) { if (!isdigit(c) && c != '-') { isNumber = false; break; } }
        if (isNumber) {
            int id = std::atoi(args.c_str());
            std::string result = argos::performUIAction(id, "click", "");
            std::this_thread::sleep_for(std::chrono::milliseconds(800));
            return result;
        }
        // Otherwise treat as text to find and click
        return argos_ui::clickElementByText(args, false);
    }

    if (name == "ui_longpress" || name == "ui_long_click") {
        if (args.empty()) return "{\"error\":\"ui_longpress needs element ID or text\"}";
        bool isNumber = true;
        for (char c : args) { if (!isdigit(c) && c != '-') { isNumber = false; break; } }
        if (isNumber) {
            int id = std::atoi(args.c_str());
            std::string result = argos::performUIAction(id, "long_click", "");
            std::this_thread::sleep_for(std::chrono::milliseconds(800));
            return result;
        }
        return argos_ui::clickElementByText(args, true);
    }

    if (name == "ui_type" || name == "ui_input") {
        // Args format: "elementId|text" or just "text" (auto-find field)
        size_t pipe = args.find('|');
        if (pipe != std::string::npos) {
            int id = std::atoi(args.substr(0, pipe).c_str());
            std::string text = args.substr(pipe + 1);
            return argos::performUIAction(id, "set_text", text);
        }
        // No element ID — find an editable field and type into it
        return argos_ui::typeIntoFieldWithHint("", args);
    }

    if (name == "ui_action") {
        // Args format: "elementId|action" or "elementId|action|extra"
        // e.g. "5|click" or "3|set_text|Hello world"
        size_t p1 = args.find('|');
        if (p1 == std::string::npos) return "{\"error\":\"ui_action needs: elementId|action[|extra]\"}";
        int id = std::atoi(args.substr(0, p1).c_str());
        size_t p2 = args.find('|', p1 + 1);
        std::string action = (p2 != std::string::npos) ? args.substr(p1 + 1, p2 - p1 - 1) : args.substr(p1 + 1);
        std::string extra = (p2 != std::string::npos) ? args.substr(p2 + 1) : "";
        std::string result = argos::performUIAction(id, action, extra);
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        return result;
    }

    if (name == "ui_sequence" || name == "ui_macro") {
        // Args is a JSON array of action steps
        // e.g. [{"action":"click","text":"Reply"},{"action":"type","text":"Hello!"},{"action":"click","text":"Send"}]
        auto steps = argos_ui::parseActionSequence(args);
        if (steps.empty()) return "{\"error\":\"No valid action steps parsed from: " + args + "\"}";
        return argos_ui::executeActionSequence(steps);
    }

    if (name == "screenshot" || name == "screen_capture") {
        return argos::takeScreenshot(args);
    }

    if (name == "notifications" || name == "get_notifications") {
        return argos::getNotificationsList();
    }

    if (name == "notif_reply" || name == "notification_reply") {
        // Args format: "index|message"
        size_t pipe = args.find('|');
        if (pipe == std::string::npos) return "{\"error\":\"notif_reply needs: index|message\"}";
        int idx = std::atoi(args.substr(0, pipe).c_str());
        std::string msg = args.substr(pipe + 1);
        return argos::replyToNotificationByIdx(idx, msg);
    }

    // ── Gesture-based UI automation tools ──

    if (name == "ui_tap_at" || name == "ui_tap_point") {
        // Args format: "x,y"
        if (args.empty()) return "{\"error\":\"ui_tap_at needs: x,y\"}";
        size_t comma = args.find(',');
        if (comma == std::string::npos) return "{\"error\":\"ui_tap_at needs: x,y (comma separated)\"}";
        int x = std::atoi(args.substr(0, comma).c_str());
        int y = std::atoi(args.substr(comma + 1).c_str());
        std::string result = argos::clickAtPoint(x, y);
        std::this_thread::sleep_for(std::chrono::milliseconds(800));
        return result;
    }

    if (name == "ui_longpress_at" || name == "ui_long_press_point") {
        if (args.empty()) return "{\"error\":\"ui_longpress_at needs: x,y\"}";
        size_t comma = args.find(',');
        if (comma == std::string::npos) return "{\"error\":\"ui_longpress_at needs: x,y (comma separated)\"}";
        int x = std::atoi(args.substr(0, comma).c_str());
        int y = std::atoi(args.substr(comma + 1).c_str());
        std::string result = argos::longPressAtPoint(x, y);
        std::this_thread::sleep_for(std::chrono::milliseconds(800));
        return result;
    }

    if (name == "ui_smart_click" || name == "ui_smart_tap") {
        // Smart click: tries accessibility action first, falls back to gesture
        if (args.empty()) return "{\"error\":\"ui_smart_click needs: x,y\"}";
        size_t comma = args.find(',');
        if (comma == std::string::npos) return "{\"error\":\"ui_smart_click needs: x,y (comma separated)\"}";
        int x = std::atoi(args.substr(0, comma).c_str());
        int y = std::atoi(args.substr(comma + 1).c_str());
        std::string result = argos::smartClick(x, y);
        std::this_thread::sleep_for(std::chrono::milliseconds(800));
        return result;
    }

    if (name == "ui_smart_longpress") {
        if (args.empty()) return "{\"error\":\"ui_smart_longpress needs: x,y\"}";
        size_t comma = args.find(',');
        if (comma == std::string::npos) return "{\"error\":\"ui_smart_longpress needs: x,y (comma separated)\"}";
        int x = std::atoi(args.substr(0, comma).c_str());
        int y = std::atoi(args.substr(comma + 1).c_str());
        std::string result = argos::smartLongPress(x, y);
        std::this_thread::sleep_for(std::chrono::milliseconds(800));
        return result;
    }

    if (name == "ui_swipe" || name == "ui_gesture_swipe") {
        // Args format: "x1,y1,x2,y2" or "x1,y1,x2,y2,duration"
        if (args.empty()) return "{\"error\":\"ui_swipe needs: x1,y1,x2,y2[,duration]\"}";
        std::vector<int> nums;
        std::stringstream ss(args);
        std::string token;
        while (std::getline(ss, token, ',')) {
            nums.push_back(std::atoi(token.c_str()));
        }
        if (nums.size() < 4) return "{\"error\":\"ui_swipe needs at least 4 values: x1,y1,x2,y2\"}";
        int duration = nums.size() > 4 ? nums[4] : 300;
        std::string result = argos::swipeGesture(nums[0], nums[1], nums[2], nums[3], duration);
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        return result;
    }

    if (name == "ui_swipe_up" || name == "swipe_up") {
        std::string result = argos::swipeUp();
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        return result;
    }

    if (name == "ui_swipe_down" || name == "swipe_down") {
        std::string result = argos::swipeDown();
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        return result;
    }

    if (name == "ui_swipe_left" || name == "swipe_left") {
        std::string result = argos::swipeLeft();
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        return result;
    }

    if (name == "ui_swipe_right" || name == "swipe_right") {
        std::string result = argos::swipeRight();
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        return result;
    }

    if (name == "ui_elements" || name == "ui_clickable") {
        // Quick list of all clickable/interactive elements with bounds and center points
        return argos::getClickableElements();
    }

    if (name == "screen_size" || name == "ui_screen_size") {
        return argos::getScreenSize();
    }

    // ── Voice / Speech tools (native TTS only — STT handled by backend) ──

    if (name == "tts_speak" || name == "speak" || name == "voice_speak") {
        // Speak text using native TTS (Android TextToSpeech / Windows SAPI)
        if (args.empty()) return "{\"error\":\"tts_speak needs: <text>\"}";
        std::string result = argos::ttsSpeakJava(args);
        if (result.find("\"error\"") == std::string::npos) {
            return "{\"status\":\"speaking\",\"text\":\"" + args + "\"}";
        }
        return "{\"error\":\"TTS not available or failed\"}";
    }

    if (name == "tts_stop" || name == "voice_stop") {
        argos::ttsStopJava();
        return "{\"status\":\"stopped\"}";
    }

    if (name == "tts_status") {
        std::string result = argos::ttsIsSpeakingJava();
        if (result.find("true") != std::string::npos) {
            return "{\"speaking\":true}";
        }
        return "{\"speaking\":false}";
    }

    // ── Robot Control Tools (3D floating robot) ──

    if (name == "robot_expression") {
        if (args.empty()) return "{\"error\":\"robot_expression needs: <expression>\"}";
        // Validate expression
        if (args == "neutral" || args == "happy" || args == "thinking" ||
            args == "talking" || args == "sleeping" || args == "surprised" ||
            args == "wink" || args == "love" || args == "angry" || args == "sad" ||
            args == "confused" || args == "excited" || args == "dizzy" ||
            args == "star_eyes" || args == "scared") {
            argos::robotSetExpression(args);
            return "{\"status\":\"expression_set\",\"expression\":\"" + args + "\"}";
        }
        return "{\"error\":\"Invalid expression. Use: neutral, happy, thinking, talking, sleeping, surprised, wink, love, angry, sad, confused, excited, dizzy, star_eyes, scared\"}";
    }

    if (name == "robot_move") {
        if (args.empty()) return "{\"error\":\"robot_move needs: <x,y>\"}";
        // Parse "x,y" format
        size_t comma = args.find(',');
        if (comma == std::string::npos) return "{\"error\":\"robot_move needs x,y format\"}";
        try {
            float x = std::stof(args.substr(0, comma));
            float y = std::stof(args.substr(comma + 1));
            argos::robotMoveTo(x, y);
            return "{\"status\":\"moving\",\"x\":" + std::to_string(x) + ",\"y\":" + std::to_string(y) + "}";
        } catch (...) {
            return "{\"error\":\"Invalid coordinates\"}";
        }
    }

    if (name == "robot_position") {
        return argos::robotGetPosition();
    }

    if (name == "robot_state") {
        if (args.empty()) return "{\"error\":\"robot_state needs: <state>\"}";
        if (args == "idle" || args == "walking" || args == "greeting" ||
            args == "thinking" || args == "talking" || args == "spinning" || args == "sleeping") {
            argos::robotSetState(args);
            return "{\"status\":\"state_set\",\"state\":\"" + args + "\"}";
        }
        return "{\"error\":\"Invalid state. Use: idle, walking, greeting, thinking, talking, spinning, sleeping\"}";
    }

    if (name == "robot_zoom") {
        if (args.empty()) return "{\"error\":\"robot_zoom needs: <scale>\"}";
        try {
            float zoom = std::stof(args);
            if (zoom < 0.2f) zoom = 0.2f;
            if (zoom > 3.0f) zoom = 3.0f;
            argos::robotSetZoom(zoom);
            return "{\"status\":\"zoom_set\",\"scale\":" + std::to_string(zoom) + "}";
        } catch (...) {
            return "{\"error\":\"Invalid zoom scale\"}";
        }
    }

    if (name == "robot_zoom_reset") {
        argos::robotResetZoom();
        return "{\"status\":\"zoom_reset\",\"mode\":\"auto_depth\"}";
    }

    if (name == "robot_blink") {
        if (args.empty()) return "{\"error\":\"robot_blink needs: <x,y>\"}";
        size_t comma = args.find(',');
        if (comma == std::string::npos) return "{\"error\":\"robot_blink needs x,y format\"}";
        try {
            float x = std::stof(args.substr(0, comma));
            float y = std::stof(args.substr(comma + 1));
            argos::robotBlinkTo(x, y);
            return "{\"status\":\"blinking\",\"x\":" + std::to_string(x) + ",\"y\":" + std::to_string(y) + "}";
        } catch (...) {
            return "{\"error\":\"Invalid coordinates\"}";
        }
    }

    // ── Screen Observation (screenshot + OCR + UI elements) ──

    if (name == "observe" || name == "look" || name == "observe_screen") {
        std::string result = argos::observeScreen();
        // Wait a moment for screen to settle after observation
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        return result;
    }

    // ── Phone Automation Tools (Play Store Compliant) ──

    if (name == "dial" || name == "call_dial" || name == "phone_dial") {
        if (args.empty()) return "{\"error\":\"dial needs: <phone_number>\"}";
        return argos::dialPhoneNumber(args);
    }

    if (name == "sms" || name == "send_sms" || name == "text_message") {
        // Args format: "number|message"
        if (args.empty()) return "{\"error\":\"sms needs: <number|message>\"}";
        size_t pipe = args.find('|');
        if (pipe == std::string::npos) return "{\"error\":\"sms needs: <number|message> (pipe separated)\"}";
        std::string number = args.substr(0, pipe);
        std::string message = args.substr(pipe + 1);
        return argos::sendSmsViaIntent(number, message);
    }

    if (name == "contacts" || name == "search_contacts" || name == "find_contact") {
        if (args.empty()) return "{\"error\":\"contacts needs: <name_or_number>\"}";
        return argos::searchContacts(args);
    }

    if (name == "calendar_new" || name == "create_event" || name == "calendar_create") {
        // Args format: "title|description|startMillis|endMillis"
        if (args.empty()) return "{\"error\":\"calendar_new needs: title|description|startMillis|endMillis\"}";
        std::vector<std::string> parts;
        std::stringstream ss(args);
        std::string part;
        while (std::getline(ss, part, '|') && parts.size() < 4) {
            parts.push_back(part);
        }
        if (parts.size() < 1) return "{\"error\":\"calendar_new needs at least a title\"}";
        std::string title = parts[0];
        std::string desc = parts.size() > 1 ? parts[1] : "";
        long start = parts.size() > 2 ? std::atol(parts[2].c_str()) : 0;
        long end = parts.size() > 3 ? std::atol(parts[3].c_str()) : 0;
        return argos::createCalendarEvent(title, desc, start, end);
    }

    if (name == "calendar_read" || name == "read_calendar" || name == "calendar_events") {
        int days = 7;
        if (!args.empty()) days = std::atoi(args.c_str());
        if (days <= 0) days = 7;
        return argos::readCalendarEvents(days);
    }

    if (name == "timer" || name == "set_timer") {
        // Args format: "seconds|label" or just "seconds"
        if (args.empty()) return "{\"error\":\"timer needs: <seconds[|label]>\"}";
        size_t pipe = args.find('|');
        int seconds = pipe != std::string::npos ? std::atoi(args.substr(0, pipe).c_str()) : std::atoi(args.c_str());
        std::string label = pipe != std::string::npos ? args.substr(pipe + 1) : "Argos Timer";
        return argos::setTimer(seconds, label);
    }

    if (name == "alarm" || name == "set_alarm") {
        // Args format: "hour:minute|label" or "hour:minute"
        if (args.empty()) return "{\"error\":\"alarm needs: <hour:minute[|label]>\"}";
        size_t pipe = args.find('|');
        std::string timePart = pipe != std::string::npos ? args.substr(0, pipe) : args;
        std::string label = pipe != std::string::npos ? args.substr(pipe + 1) : "Argos Alarm";
        size_t colon = timePart.find(':');
        if (colon == std::string::npos) return "{\"error\":\"alarm needs hour:minute format\"}";
        int hour = std::atoi(timePart.substr(0, colon).c_str());
        int minute = std::atoi(timePart.substr(colon + 1).c_str());
        return argos::setAlarm(hour, minute, label);
    }

    if (name == "battery" || name == "battery_status" || name == "power_status") {
        return argos::getBatteryStatus();
    }

    if (name == "flashlight" || name == "torch") {
        std::string mode = args;
        for (auto& c : mode) c = tolower(c);
        bool on = (mode == "on" || mode == "true" || mode == "1" || mode.empty());
        return argos::toggleFlashlight(on);
    }

    if (name == "maps" || name == "open_maps" || name == "search_maps") {
        if (args.empty()) return "{\"error\":\"maps needs: <location_query>\"}";
        return argos::openMaps(args);
    }

    if (name == "navigate" || name == "navigation" || name == "start_navigation") {
        if (args.empty()) return "{\"error\":\"navigate needs: <destination>\"}";
        return argos::startNavigation(args);
    }

    if (name == "share" || name == "share_content" || name == "share_text") {
        if (args.empty()) return "{\"error\":\"share needs: <text_to_share>\"}";
        return argos::shareContent(args);
    }

    if (name == "music" || name == "play_music" || name == "play_song") {
        if (args.empty()) return "{\"error\":\"music needs: <song_or_artist_query>\"}";
        return argos::playMusic(args);
    }

    if (name == "settings" || name == "open_settings") {
        std::string type = args.empty() ? "main" : args;
        return argos::openSettings(type);
    }

    if (name == "clipboard_read" || name == "read_clipboard" || name == "paste_clipboard") {
        return argos::readClipboard();
    }

    if (name == "location" || name == "get_location" || name == "where_am_i") {
        return argos::getLocation();
    }

    if (name == "go_back" || name == "back") {
        return argos::goBack();
    }

    if (name == "go_home" || name == "home") {
        return argos::goHome();
    }

    if (name == "recents" || name == "open_recents" || name == "recent_apps") {
        return argos::openRecents();
    }

    return "{\"error\":\"Unknown tool: " + name + "\"}";
}

} // namespace argos_tools
