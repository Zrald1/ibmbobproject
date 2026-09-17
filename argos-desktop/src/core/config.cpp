#include "core/config.h"

#include <fstream>

#include <nlohmann/json.hpp>

#include "core/log.h"
#include "platform/win_util.h"

namespace argos {
namespace {

nlohmann::json to_json(const Config& c) {
    using nlohmann::json;
    return json{
        {"cerebras",
         {
             {"api_key", win::dpapi_protect(c.cerebras.api_key)},
             {"base_url", c.cerebras.base_url},
             {"model", c.cerebras.model},
             {"vision_model", c.cerebras.vision_model},
             {"reasoning_effort", c.cerebras.reasoning_effort},
             {"max_tokens", c.cerebras.max_tokens},
             {"temperature", c.cerebras.temperature},
             {"timeout_seconds", c.cerebras.timeout_seconds},
         }},
        {"assemblyai",
         {
             {"api_key", win::dpapi_protect(c.assemblyai.api_key)},
             {"ws_url", c.assemblyai.ws_url},
             {"speech_model", c.assemblyai.speech_model},
             {"language_codes", c.assemblyai.language_codes},
             {"language_detection", c.assemblyai.language_detection},
             {"mode", c.assemblyai.mode},
         }},
        {"audio",
         {
             {"source", c.audio.source},
             {"input_device", c.audio.input_device},
             {"autosave_seconds", c.audio.autosave_seconds},
             {"auto_start", c.audio.auto_start},
             {"hotkey", c.audio.hotkey},
         }},
        {"assistant",
         {
             {"tts_enabled", c.assistant.tts_enabled},
             {"speak_replies", c.assistant.speak_replies},
             {"tts_voice", c.assistant.tts_voice},
             {"tts_rate", c.assistant.tts_rate},
             {"tts_volume", c.assistant.tts_volume},
             {"screen_context", c.assistant.screen_context},
             {"history_turns", c.assistant.history_turns},
             {"proactive_thoughts", c.assistant.proactive_thoughts},
             {"thought_interval_seconds", c.assistant.thought_interval_seconds},
         }},
        {"overlay",
         {
             {"enabled", c.overlay.enabled},
             {"robot_size", c.overlay.robot_size},
             {"robot_zoom", c.overlay.robot_zoom},
             {"robot_opacity_percent", c.overlay.robot_opacity_percent},
             {"always_on_top", c.overlay.always_on_top},
             {"roam", c.overlay.roam},
             {"captions_enabled", c.overlay.captions_enabled},
             {"caption_font_size", c.overlay.caption_font_size},
             {"caption_seconds", c.overlay.caption_seconds},
         }},
        {"tools",
         {
             {"allow_destructive", c.tools.allow_destructive},
             {"notes_dir", c.tools.notes_dir},
             {"screenshot_dir", c.tools.screenshot_dir},
             {"transcript_dir", c.tools.transcript_dir},
         }},
        {"phone",
         {
             {"enabled", c.phone.enabled},
             {"port", c.phone.port},
             {"token", c.phone.token.empty() ? std::string()
                                           : win::dpapi_protect(c.phone.token)},
         }},
        {"link",
         {
             {"enabled", c.link.enabled},
             {"base_url", c.link.base_url},
             {"fallback_url", c.link.fallback_url},
             {"desktop_id", c.link.desktop_id},
             {"desktop_token", c.link.desktop_token.empty()
                                   ? std::string()
                                   : win::dpapi_protect(c.link.desktop_token)},
         }},
    };
}

// Reads a value, tolerating missing keys and wrong types.
template <typename T>
void pick(const nlohmann::json& j, const char* key, T& out) {
    if (auto it = j.find(key); it != j.end() && !it->is_null()) {
        try {
            out = it->get<T>();
        } catch (const nlohmann::json::exception&) {
            // keep the default
        }
    }
}

std::string pick_secret(const nlohmann::json& j, const char* key) {
    std::string stored;
    pick(j, key, stored);
    if (stored.empty()) return {};
    if (stored.rfind("dpapi:", 0) == 0) {
        if (auto plain = win::dpapi_unprotect(stored)) return *plain;
        log::warn("Could not decrypt stored API key — re-enter it in Settings.");
        return {};
    }
    return stored;  // tolerate a hand-edited plaintext key
}

void from_json(const nlohmann::json& j, Config& c) {
    if (auto it = j.find("cerebras"); it != j.end()) {
        const auto& n = *it;
        c.cerebras.api_key = pick_secret(n, "api_key");
        pick(n, "base_url", c.cerebras.base_url);
        pick(n, "model", c.cerebras.model);
        pick(n, "vision_model", c.cerebras.vision_model);
        pick(n, "reasoning_effort", c.cerebras.reasoning_effort);
        pick(n, "max_tokens", c.cerebras.max_tokens);
        pick(n, "temperature", c.cerebras.temperature);
        pick(n, "timeout_seconds", c.cerebras.timeout_seconds);
    }
    if (auto it = j.find("assemblyai"); it != j.end()) {
        const auto& n = *it;
        c.assemblyai.api_key = pick_secret(n, "api_key");
        pick(n, "ws_url", c.assemblyai.ws_url);
        pick(n, "speech_model", c.assemblyai.speech_model);
        pick(n, "language_codes", c.assemblyai.language_codes);
        pick(n, "language_detection", c.assemblyai.language_detection);
        pick(n, "mode", c.assemblyai.mode);
    }
    if (auto it = j.find("audio"); it != j.end()) {
        const auto& n = *it;
        pick(n, "source", c.audio.source);
        pick(n, "input_device", c.audio.input_device);
        pick(n, "autosave_seconds", c.audio.autosave_seconds);
        pick(n, "auto_start", c.audio.auto_start);
        pick(n, "hotkey", c.audio.hotkey);
    }
    if (auto it = j.find("assistant"); it != j.end()) {
        const auto& n = *it;
        pick(n, "tts_enabled", c.assistant.tts_enabled);
        pick(n, "speak_replies", c.assistant.speak_replies);
        pick(n, "tts_voice", c.assistant.tts_voice);
        pick(n, "tts_rate", c.assistant.tts_rate);
        pick(n, "tts_volume", c.assistant.tts_volume);
        pick(n, "screen_context", c.assistant.screen_context);
        pick(n, "history_turns", c.assistant.history_turns);
        pick(n, "proactive_thoughts", c.assistant.proactive_thoughts);
        pick(n, "thought_interval_seconds", c.assistant.thought_interval_seconds);
    }
    if (auto it = j.find("overlay"); it != j.end()) {
        const auto& n = *it;
        pick(n, "enabled", c.overlay.enabled);
        pick(n, "robot_size", c.overlay.robot_size);
        pick(n, "robot_zoom", c.overlay.robot_zoom);
        pick(n, "robot_opacity_percent", c.overlay.robot_opacity_percent);
        pick(n, "always_on_top", c.overlay.always_on_top);
        pick(n, "roam", c.overlay.roam);
        pick(n, "captions_enabled", c.overlay.captions_enabled);
        pick(n, "caption_font_size", c.overlay.caption_font_size);
        pick(n, "caption_seconds", c.overlay.caption_seconds);
    }
    if (auto it = j.find("tools"); it != j.end()) {
        const auto& n = *it;
        pick(n, "allow_destructive", c.tools.allow_destructive);
        pick(n, "notes_dir", c.tools.notes_dir);
        pick(n, "screenshot_dir", c.tools.screenshot_dir);
        pick(n, "transcript_dir", c.tools.transcript_dir);
    }
    if (auto it = j.find("phone"); it != j.end()) {
        const auto& n = *it;
        pick(n, "enabled", c.phone.enabled);
        pick(n, "port", c.phone.port);
        c.phone.token = pick_secret(n, "token");
    }
    if (auto it = j.find("link"); it != j.end()) {
        const auto& n = *it;
        pick(n, "enabled", c.link.enabled);
        pick(n, "base_url", c.link.base_url);
        pick(n, "fallback_url", c.link.fallback_url);
        pick(n, "desktop_id", c.link.desktop_id);
        c.link.desktop_token = pick_secret(n, "desktop_token");
    }
}

}  // namespace

Config& config() {
    static Config instance;
    return instance;
}

std::filesystem::path config_path() { return win::app_data_dir() / L"config.json"; }

bool config_load() {
    const auto path = config_path();
    Config fresh;  // defaults

    std::ifstream in(path, std::ios::binary);
    if (!in.is_open()) {
        config() = fresh;
        log::info("No config.json yet — created defaults at " + win::to_utf8(path.wstring()));
        return config_save();
    }

    try {
        nlohmann::json j;
        in >> j;
        from_json(j, fresh);
        config() = fresh;
        log::info("Loaded config from " + win::to_utf8(path.wstring()));
        return true;
    } catch (const std::exception& e) {
        log::error(std::string("config.json is unreadable (") + e.what() +
                   ") — falling back to defaults; the broken file is kept as config.json.bad");
        std::error_code ec;
        std::filesystem::rename(path, path.string() + ".bad", ec);
        config() = fresh;
        return config_save();
    }
}

bool config_save() {
    const auto path = config_path();
    const auto tmp = path.string() + ".tmp";

    try {
        std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
        if (!out.is_open()) {
            log::error("Could not open config for writing: " + tmp);
            return false;
        }
        out << to_json(config()).dump(2) << '\n';
        out.close();

        std::error_code ec;
        std::filesystem::rename(tmp, path, ec);
        if (ec) {
            std::filesystem::remove(path, ec);
            std::filesystem::rename(tmp, path, ec);
        }
        if (ec) {
            log::error("Could not replace config.json: " + ec.message());
            return false;
        }
        return true;
    } catch (const std::exception& e) {
        log::error(std::string("Failed to save config: ") + e.what());
        return false;
    }
}

}  // namespace argos
