#pragma once

// Application configuration.
//
// Lives OUTSIDE the source tree, in %APPDATA%\ArgosDesktop\config.json, so it
// can never be committed to git. API keys are encrypted with Windows DPAPI
// (current-user scope) before they touch the disk.

#include <filesystem>
#include <string>
#include <vector>

namespace argos {

struct Config {
    struct Cerebras {
        std::string api_key;  // plaintext in memory, DPAPI blob on disk
        std::string base_url = "https://api.cerebras.ai/v1";
        std::string model = "gpt-oss-120b";
        std::string vision_model = "qwen-3.8-27b";
        std::string reasoning_effort = "medium";
        int max_tokens = 400;
        double temperature = 0.7;
        int timeout_seconds = 60;
    } cerebras;

    // Your own backend (java-backend / FastAPI relay). When enabled and no
    // Cerebras key is set, chat routes to {base_url}/api/chat — the backend
    // owns the provider keys (gpt-6-astra, gemini-flash, fable, stt).
    struct Backend {
        bool enabled = false;
        std::string base_url = "http://localhost:8080";
        std::string api_key;        // plaintext in memory, DPAPI blob on disk
        std::string default_model;  // optional model override (X-Argos-Model)
        int timeout_seconds = 30;
    } backend;

    struct SubAgent {
        std::string name;    // becomes the ask_<name> tool
        std::string model;   // Bitdeer catalog id, e.g. "deepseek-ai/DeepSeek-V4.1-Flash"
        std::string prompt;  // specialty system prompt ("you are a code reviewer…")
    };

    struct Bitdeer {
        std::string api_key;  // plaintext in memory, DPAPI blob on disk
        std::string base_url = "https://api-inference.bitdeer.ai/v1";
        int max_tokens = 2048;
        double temperature = 0.7;
        int timeout_seconds = 90;
        // User-defined sub-agents the Cerebras brain can delegate to.
        std::vector<SubAgent> agents;
    } bitdeer;

    struct AssemblyAI {
        std::string api_key;  // plaintext in memory, DPAPI blob on disk
        // REST endpoint base for upload+transcript (overridable for proxies
        // and local test mocks).
        std::string api_base = "https://api.assemblyai.com";
        std::string ws_url = "wss://streaming.assemblyai.com/v3/ws";
        std::string speech_model = "universal-3-5-pro";
        std::string language_codes;  // JSON array text, e.g. ["en"]; empty = no steering
        bool language_detection = false;
        std::string mode;  // "", "balanced", "min_latency", "max_accuracy"
    } assemblyai;

    // Murf AI text-to-speech — neural voices, much smoother than SAPI.
    // When api_key is set, replies speak through Murf instead of Windows.
    struct Murf {
        std::string api_key;  // plaintext in memory, DPAPI blob on disk
        std::string base_url = "https://api.murf.ai/v1";
        std::string voice = "terrell";  // male en-US voice (Falcon/Gen2)
        int rate = 0;   // -50..50
        int pitch = 0;  // -50..50
    } murf;

    // Speechmatics TTS — second neural engine (preview endpoint).
    struct Speechmatics {
        std::string api_key;  // plaintext in memory, DPAPI blob on disk
        std::string base_url = "https://preview.tts.speechmatics.com";
        std::string voice = "jack";  // jack/theo (M), megan/sarah (F)
    } speechmatics;

    // Local Voicebox AI studio (voicebox-main) TTS engine.
    struct Voicebox {
        bool enabled = true;
        std::string base_url = "http://127.0.0.1:8000";
        std::string profile = "default";
    } voicebox;

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
        // Which TTS engine speaks replies: "voicebox" | "murf" | "speechmatics" | "sapi".
        // Empty/unavailable engines fall back through voicebox → murf → speechmatics → sapi.
        std::string tts_engine = "sapi";
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

    // External MCP servers. Each server's tools are advertised to the
    // Cerebras brain as mcp_<name>_<tool> and routed back over the wire the
    // server was connected on.
    struct McpServer {
        std::string name;     // prefix: mcp_<name>_<tool>
        std::string type;     // "stdio" | "http" (streamable HTTP)
        std::string command;  // stdio: executable, e.g. "npx"
        std::string args;     // stdio: full argument line
        std::string url;      // http: endpoint, e.g. http://localhost:8080/mcp
        std::string token;    // http: optional bearer (DPAPI on disk)
        bool enabled = true;
    };

    struct Mcp {
        std::vector<McpServer> servers;
    } mcp;
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
