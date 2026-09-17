#pragma once

// Application configuration.
//
// Lives OUTSIDE the source tree, in %APPDATA%\ArgosDesktop\config.json, so it
// can never be committed to git. API keys are encrypted with Windows DPAPI
// (current-user scope) before they touch the disk.

#include <filesystem>
#include <string>

namespace argos {

struct Config {
    struct Cerebras {
        std::string api_key;  // plaintext in memory, DPAPI blob on disk
        std::string base_url = "https://api.cerebras.ai/v1";
        std::string model = "gpt-oss-120b";
        std::string vision_model = "qwen-3.8-27b";
        std::string reasoning_effort = "none";
        int max_tokens = 400;
        double temperature = 0.7;
        int timeout_seconds = 60;
    } cerebras;

    struct AssemblyAI {
        std::string api_key;  // plaintext in memory, DPAPI blob on disk
        std::string ws_url = "wss://streaming.assemblyai.com/v3/ws";
        std::string speech_model = "universal-3-5-pro";
        std::string language_codes;  // JSON array text, e.g. ["en"]; empty = no steering
        bool language_detection = false;
        std::string mode;  // "", "balanced", "min_latency", "max_accuracy"
    } assemblyai;

    struct Audio {
        std::string source = "microphone";  // microphone | system | both
        std::string input_device;           // empty = system default
        int autosave_seconds = 30;
        bool auto_start = false;
        std::string hotkey = "Ctrl+Alt+Space";
    } audio;

    struct Assistant {
        bool tts_enabled = true;
        bool speak_replies = true;
        std::string tts_voice;  // empty = default SAPI voice
        int tts_rate = 1;       // -10..10
        int tts_volume = 100;   // 0..100
        bool screen_context = true;
        int history_turns = 12;
        bool proactive_thoughts = false;
        int thought_interval_seconds = 180;
    } assistant;

    struct Overlay {
        bool enabled = true;
        int robot_size = 240;
        // Extra on-screen magnification applied on top of the Android box
        // padding (2.0x wide / 1.7x tall). 1.0 is phone parity; 1.5 reads well
        // on a 1080p desktop monitor.
        double robot_zoom = 1.5;
        int robot_opacity_percent = 100;
        bool always_on_top = true;
        bool roam = true;
        bool captions_enabled = true;
        int caption_font_size = 22;
        int caption_seconds = 8;  // how long a finished caption stays up
    } overlay;

    struct Tools {
        bool allow_destructive = false;  // skip confirmation for delete/move
        std::string notes_dir;           // empty = Documents\Argos\notes
        std::string screenshot_dir;      // empty = Documents\Argos\screenshots
        std::string transcript_dir;      // empty = Documents\Argos\transcripts
    } tools;

    struct Phone {
        bool enabled = true;
        int port = 47830;
        // Pairing secret shared with the Android app via QR code.
        // Plaintext in memory (it must render into the QR), DPAPI blob on disk.
        // Regenerating it revokes every paired phone.
        std::string token;
    } phone;

    struct Link {
        bool enabled = true;
        // Backend relay the phone talks through (matches the Android app's
        // backend_url / fallback_backend_url).
        std::string base_url = "http://47.129.187.134";
        std::string fallback_url = "http://47.129.187.134/java";
        // Persistent desktop identity — kept across restarts so paired phones
        // stay paired. desktop_token is DPAPI-protected on disk.
        std::string desktop_id;
        std::string desktop_token;
    } link;
};

// Global config (mutated by the settings UI).
Config& config();

std::filesystem::path config_path();

// Reads config.json; creates it with defaults when missing.
// Missing/invalid keys are left blank so the UI can prompt for them.
bool config_load();

// Writes config.json atomically (temp file + rename), encrypting secrets.
bool config_save();

}  // namespace argos
