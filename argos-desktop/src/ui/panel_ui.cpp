// Main panel UI: chat, live transcript, settings (API keys), tools and log.
// Drawn by the App each frame inside the ImGui context of PanelWindow.

#include <windows.h>

#include <shellapi.h>

#include <imgui.h>
#include <imgui_stdlib.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <future>
#include <sstream>
#include <string>
#include <thread>

#include "core/app.h"
#include "core/config.h"
#include "core/log.h"
#include "ui/panel_window.h"
#include "link/link_client.h"
#include "mcp/mcp_client.h"
#include "phone/phone_server.h"
#include "platform/win_util.h"
#include "tools/tools.h"
#include "vendor/qrcodegen.hpp"

namespace argos {
namespace {

const char* const kAudioSources[] = {"microphone", "system", "both"};

bool combo_from_list(const char* label, std::string& value, const char* const* items, int count) {
    int current = 0;
    for (int i = 0; i < count; ++i) {
        if (value == items[i]) current = i;
    }
    bool changed = false;
    if (ImGui::BeginCombo(label, items[current])) {
        for (int i = 0; i < count; ++i) {
            const bool selected = (i == current);
            if (ImGui::Selectable(items[i], selected)) {
                value = items[i];
                changed = true;
            }
            if (selected) ImGui::SetItemDefaultFocus();
        }
        ImGui::EndCombo();
    }
    return changed;
}

// A secret field: masked by default with a reveal toggle, plus a paste hint.
bool secret_input(const char* id, char* buffer, size_t size, bool& reveal) {
    ImGui::PushID(id);
    const ImGuiInputTextFlags flags =
        reveal ? ImGuiInputTextFlags_None : ImGuiInputTextFlags_Password;
    ImGui::SetNextItemWidth(-90.0f);
    const bool edited = ImGui::InputText("##secret", buffer, size, flags);
    ImGui::SameLine();
    if (ImGui::Button(reveal ? "Hide" : "Show")) reveal = !reveal;
    ImGui::PopID();
    return edited;
}

void help_marker(const char* text) {
    ImGui::SameLine();
    ImGui::TextDisabled("(?)");
    if (ImGui::IsItemHovered()) {
        ImGui::BeginTooltip();
        ImGui::PushTextWrapPos(ImGui::GetFontSize() * 32.0f);
        ImGui::TextUnformatted(text);
        ImGui::PopTextWrapPos();
        ImGui::EndTooltip();
    }
}

void draw_status_bar() {
    Config& cfg = config();
    const bool backend_ok = cfg.backend.enabled && !cfg.backend.base_url.empty();

    auto dot = [](bool ok, const char* label) {
        ImGui::TextColored(ok ? ImVec4(0.20f, 0.90f, 0.55f, 1.0f) : ImVec4(0.95f, 0.45f, 0.30f, 1.0f),
                           ok ? "\xe2\x97\x8f" : "\xe2\x97\x8b");
        ImGui::SameLine();
        ImGui::TextUnformatted(label);
    };

    dot(backend_ok, "Backend");
    ImGui::SameLine(0, 18);
    ImGui::TextDisabled("|");
    ImGui::SameLine(0, 18);
    ImGui::Text("Overlay: %s", cfg.overlay.enabled ? "on" : "off");
    ImGui::SameLine(0, 18);
    ImGui::Text("STT source: %s", cfg.audio.source.c_str());
}

void draw_settings_tab(App& application) {
    Config& cfg = config();
    bool dirty = false;

    if (ImGui::BeginChild("settings_scroll", ImVec2(0, -44), ImGuiChildFlags_None)) {
        // ── Backend relay ──
        ImGui::SeparatorText("Backend (chat · STT · providers)");
        help_marker("All AI routes through your backend: chat to {base}/api/chat, "
                    "speech-to-text to /api/transcribe. The backend owns the "
                    "provider keys (gpt-6-astra, gemini-flash, fable, stt) — "
                    "Test shows which are live vs simulation.");
        if (ImGui::Checkbox("Route chat through backend", &cfg.backend.enabled))
            dirty = true;
        ImGui::SetNextItemWidth(-40);
        if (ImGui::InputText("Backend URL", &cfg.backend.base_url)) dirty = true;
        ImGui::SetItemTooltip("e.g. http://localhost:8080 (java-backend) or "
                              "http://localhost:8000 (FastAPI backend)");
        ImGui::SetNextItemWidth(-40);
        if (ImGui::InputText("Default model", &cfg.backend.default_model))
            dirty = true;
        ImGui::SetItemTooltip("Optional — sent as the model field/X-Argos-Model");
        ImGui::TextUnformatted("API key (optional bearer)");
        if (secret_input("backend_key", application.backend_key_buffer(),
                         App::key_buffer_size, application.reveal_backend_key())) {
            dirty = true;
        }
        ImGui::SetItemTooltip("Only needed if your backend requires auth — "
                              "java-backend /api/chat accepts open calls");
        static std::future<std::string> backend_probe;
        static std::string backend_result;
        if (backend_probe.valid() &&
            backend_probe.wait_for(std::chrono::seconds(0)) ==
                std::future_status::ready) {
            backend_result = backend_probe.get();
        }
        if (ImGui::SmallButton("Test backend")) {
            backend_result.clear();
            backend_probe = std::async(std::launch::async, []() -> std::string {
                auto r = tools::execute("backend_status", {});
                return r.ok ? r.output : "error: " + r.output;
            });
        }
        ImGui::SameLine();
        help_marker("Probes /api/health + /api/models — shows which providers "
                    "have keys configured on the backend side");
        if (backend_probe.valid())
            ImGui::TextDisabled("probing backend…");
        if (!backend_result.empty())
            ImGui::TextWrapped("%s", backend_result.c_str());

        ImGui::Spacing();
        ImGui::SeparatorText("MCP servers (external tools)");
        help_marker("Attach any MCP server — its tools appear to the brain as "
                    "mcp_<name>_<tool>. stdio spawns a local command (npx, python, a binary); "
                    "http posts JSON-RPC to a streamable-HTTP endpoint.");
        int mcp_remove = -1;
        for (size_t i = 0; i < cfg.mcp.servers.size(); ++i) {
            auto& s = cfg.mcp.servers[i];
            ImGui::PushID(1000 + (int)i);
            if (ImGui::Checkbox("##en", &s.enabled)) dirty = true;
            ImGui::SameLine();
            ImGui::SetNextItemWidth(110);
            if (ImGui::InputTextWithHint("##name", "name", &s.name)) dirty = true;
            ImGui::SameLine();
            ImGui::SetNextItemWidth(75);
            const char* kTypes[] = {"stdio", "http"};
            int tcur = s.type == "http" ? 1 : 0;
            if (ImGui::Combo("##type", &tcur, kTypes, 2)) {
                s.type = kTypes[tcur];
                dirty = true;
            }
            ImGui::SameLine();
            if (s.type == "http") {
                ImGui::SetNextItemWidth(240);
                if (ImGui::InputTextWithHint("##url", "http://host:port/mcp", &s.url))
                    dirty = true;
                ImGui::SameLine();
                ImGui::SetNextItemWidth(120);
                if (ImGui::InputTextWithHint("##tok", "bearer (opt)", &s.token,
                                             ImGuiInputTextFlags_Password))
                    dirty = true;
            } else {
                ImGui::SetNextItemWidth(140);
                if (ImGui::InputTextWithHint("##cmd", "command e.g. npx", &s.command))
                    dirty = true;
                ImGui::SameLine();
                ImGui::SetNextItemWidth(-1);
                if (ImGui::InputTextWithHint("##args",
                        "args e.g. -y @modelcontextprotocol/server-filesystem C:\\",
                        &s.args))
                    dirty = true;
            }
            ImGui::SameLine();
            if (ImGui::SmallButton("x")) mcp_remove = (int)i;
            ImGui::PopID();
        }
        if (mcp_remove >= 0) {
            cfg.mcp.servers.erase(cfg.mcp.servers.begin() + mcp_remove);
            dirty = true;
        }
        if (ImGui::SmallButton("+ Add MCP server")) {
            cfg.mcp.servers.push_back({"fs", "stdio", "npx",
                                       "-y @modelcontextprotocol/server-filesystem C:\\",
                                       "", "", true});
            dirty = true;
        }
        ImGui::SameLine();
        if (ImGui::SmallButton("Reconnect")) mcp::client().connect_all();
        ImGui::SameLine();
        // Live per-server status so the user can see the handshake result.
        auto st = mcp::client().status();
        if (mcp::client().connecting()) {
            ImGui::TextColored(ImVec4(1, 0.8f, 0.2f, 1), "connecting…");
        } else if (st.empty()) {
            ImGui::TextDisabled("no servers");
        } else {
            for (const auto& s : st) {
                ImGui::SameLine();
                if (s.connected)
                    ImGui::TextColored(ImVec4(0.3f, 1, 0.4f, 1), "%s:%d",
                                       s.name.c_str(), s.tool_count);
                else
                    ImGui::TextColored(ImVec4(1, 0.4f, 0.3f, 1), "%s:!",
                                       s.name.c_str());
                if (ImGui::IsItemHovered())
                    ImGui::SetTooltip("%s", s.connected
                                                ? s.server_info.c_str()
                                                : s.error.c_str());
            }
        }

        ImGui::Spacing();
        ImGui::SeparatorText("Voice reply (TTS)");
        {
            const char* engines[] = {"Murf (neural)", "Speechmatics (neural)",
                                     "Windows built-in"};
            const char* ids[] = {"murf", "speechmatics", "sapi"};
            int cur = 0;
            for (int i = 0; i < 3; ++i)
                if (cfg.assistant.tts_engine == ids[i]) cur = i;
            ImGui::SetNextItemWidth(260);
            if (ImGui::BeginCombo("Engine", engines[cur])) {
                for (int i = 0; i < 3; ++i)
                    if (ImGui::Selectable(engines[i], i == cur)) {
                        cfg.assistant.tts_engine = ids[i];
                        dirty = true;
                    }
                ImGui::EndCombo();
            }
            ImGui::SameLine();
            help_marker("Falls back to Windows SAPI if the neural engine is offline.");
        }

        ImGui::Spacing();
        ImGui::TextUnformatted("Murf");
        ImGui::SameLine(80);
        if (secret_input("murf_key", application.murf_key_buffer(),
                         App::key_buffer_size, application.reveal_murf_key())) {
            dirty = true;
        }
        {
            // Live voiceIds from GET /v1/speech/voices — locale-prefixed.
            static const char* kMurfVoices[] = {
                "en-US-terrell", "en-US-miles", "en-US-cooper", "en-US-daniel",
                "en-US-wayne", "en-US-carter", "en-US-denzel", "en-US-ronnie",
                "en-US-ryan", "en-US-marcus", "en-US-caleb", "en-US-charles",
                "en-US-dylan", "en-US-evander", "en-US-paul", "en-US-lucas",
                "en-US-jayden", "en-US-ken", "en-US-maverick", "en-US-edmund",
                "en-US-natalie", "en-US-alina", "en-US-ariana", "en-US-amara",
                "en-US-alicia", "en-US-angela", "en-US-daisy", "en-US-delilah",
                "en-US-imani", "en-US-josie", "en-US-julia", "en-US-molly",
                "en-US-samantha", "en-US-abigail", "en-US-claire",
                "en-US-charlotte", "en-US-michelle", "en-US-phoebe",
                "en-US-iris", "en-US-naomi", "en-US-june", "en-US-riley",
                "en-UK-theo", "en-UK-hugo", "en-UK-gabriel", "en-UK-harrison",
                "en-UK-finley", "en-UK-mason", "en-UK-jaxon", "en-UK-reggie",
                "en-UK-hazel", "en-UK-juliet", "en-UK-heidi", "en-UK-amber",
                "en-UK-pearl", "en-UK-ruby", "en-UK-katie", "en-AU-shane",
                "en-AU-ashton", "en-AU-leyton", "en-AU-mitch", "en-AU-jimm",
                "en-AU-joyce", "en-AU-ivy", "en-AU-evelyn", "en-AU-sophia",
                "en-AU-kylie", "en-AU-harper", "en-SCOTT-rory",
                "en-SCOTT-emily"};
            ImGui::SetNextItemWidth(200);
            if (ImGui::BeginCombo("Voice##murf", cfg.murf.voice.c_str())) {
                for (const char* v : kMurfVoices)
                    if (ImGui::Selectable(v, cfg.murf.voice == v)) {
                        cfg.murf.voice = v;
                        dirty = true;
                    }
                ImGui::EndCombo();
            }
            ImGui::SameLine();
            help_marker("Voice ids are locale-prefixed (en-US-*, en-UK-*, "
                        "en-AU-*, en-SCOTT-*). Non-English ids (fr-FR-*, "
                        "de-DE-*, ja-JP-*…) can be set in config.json.");
        }
        ImGui::SetNextItemWidth(120);
        if (ImGui::SliderInt("Rate##murf", &cfg.murf.rate, -50, 50)) dirty = true;
        ImGui::SameLine();
        ImGui::SetNextItemWidth(120);
        if (ImGui::SliderInt("Pitch##murf", &cfg.murf.pitch, -50, 50)) dirty = true;

        ImGui::TextUnformatted("Speechmatics");
        ImGui::SameLine(80);
        if (secret_input("sm_key", application.speechmatics_key_buffer(),
                         App::key_buffer_size,
                         application.reveal_speechmatics_key())) {
            dirty = true;
        }
        {
            static const char* kSmVoices[] = {"jack", "theo", "megan", "sarah"};
            static const char* kSmDesc[] = {"jack — US male", "theo — UK male",
                                            "megan — US female",
                                            "sarah — UK female"};
            ImGui::SetNextItemWidth(200);
            if (ImGui::BeginCombo("Voice##sm", cfg.speechmatics.voice.c_str())) {
                for (int i = 0; i < 4; ++i)
                    if (ImGui::Selectable(kSmDesc[i],
                                          cfg.speechmatics.voice == kSmVoices[i])) {
                        cfg.speechmatics.voice = kSmVoices[i];
                        dirty = true;
                    }
                ImGui::EndCombo();
            }
        }

        ImGui::Spacing();
        ImGui::SeparatorText("Audio capture");
        ImGui::SetNextItemWidth(200);
        if (combo_from_list("Source", cfg.audio.source, kAudioSources, IM_ARRAYSIZE(kAudioSources)))
            dirty = true;
        ImGui::SameLine();
        help_marker("microphone = your voice. system = whatever the PC is playing "
                    "(WASAPI loopback). both = mix the two.");
        ImGui::SetNextItemWidth(160);
        if (ImGui::SliderInt("Autosave every (s)", &cfg.audio.autosave_seconds, 5, 300)) dirty = true;
        ImGui::SameLine();
        help_marker("The live transcript is flushed to disk on this interval.");

        ImGui::Spacing();
        ImGui::SeparatorText("Assistant");
        if (ImGui::Checkbox("Speak replies (SAPI voice)", &cfg.assistant.tts_enabled)) dirty = true;
        ImGui::SameLine();
        help_marker("Word-boundary events drive the robot's mouth and hand gestures.");
        ImGui::SetNextItemWidth(200);
        if (ImGui::SliderInt("Voice rate", &cfg.assistant.tts_rate, -10, 10)) dirty = true;
        ImGui::SetNextItemWidth(200);
        if (ImGui::SliderInt("Voice volume", &cfg.assistant.tts_volume, 0, 100)) dirty = true;
        if (ImGui::Checkbox("Send a short screen description with each message",
                            &cfg.assistant.screen_context))
            dirty = true;
        ImGui::SetNextItemWidth(160);
        if (ImGui::SliderInt("History turns", &cfg.assistant.history_turns, 0, 40)) dirty = true;

        ImGui::Spacing();
        ImGui::SeparatorText("Robot overlay");
        if (ImGui::Checkbox("Floating robot on the desktop", &cfg.overlay.enabled)) dirty = true;
        ImGui::SetNextItemWidth(200);
        if (ImGui::SliderInt("Robot size", &cfg.overlay.robot_size, 120, 520)) dirty = true;
        ImGui::SetNextItemWidth(200);
        float zoom = static_cast<float>(cfg.overlay.robot_zoom);
        if (ImGui::SliderFloat("On-screen zoom", &zoom, 0.6f, 3.0f, "%.2fx")) {
            cfg.overlay.robot_zoom = zoom;
            dirty = true;
        }
        ImGui::SameLine();
        help_marker("The Three.js scene sizes itself for a phone. Zoom magnifies it for a "
                    "desktop monitor and gives the aura rings and hands room to render. "
                    "Takes effect on the next restart.");
        ImGui::SetNextItemWidth(200);
        if (ImGui::SliderInt("Opacity %", &cfg.overlay.robot_opacity_percent, 20, 100)) dirty = true;
        if (ImGui::Checkbox("Always on top", &cfg.overlay.always_on_top)) dirty = true;
        ImGui::SameLine();
        if (ImGui::Checkbox("Let it roam the screen", &cfg.overlay.roam)) dirty = true;
        if (ImGui::Checkbox("Show live captions", &cfg.overlay.captions_enabled)) dirty = true;
        ImGui::SetNextItemWidth(200);
        if (ImGui::SliderInt("Caption size", &cfg.overlay.caption_font_size, 14, 48)) dirty = true;
        ImGui::SetNextItemWidth(200);
        if (ImGui::SliderInt("Caption linger (s)", &cfg.overlay.caption_seconds, 2, 30)) dirty = true;

        ImGui::Spacing();
        ImGui::SeparatorText("Tools & files");
        if (ImGui::Checkbox("Allow delete/move without confirmation",
                            &cfg.tools.allow_destructive))
            dirty = true;
        ImGui::SameLine();
        help_marker("Off by default: destructive file operations pop up a confirmation "
                    "card on the robot instead of running straight away.");
        ImGui::SetNextItemWidth(520);
        if (ImGui::InputTextWithHint("Notes folder", "(default) Documents\\Argos\\notes",
                                     &cfg.tools.notes_dir))
            dirty = true;
        ImGui::SetNextItemWidth(520);
        if (ImGui::InputTextWithHint("Screenshots folder", "(default) Documents\\Argos\\screenshots",
                                     &cfg.tools.screenshot_dir))
            dirty = true;
        ImGui::SetNextItemWidth(520);
        if (ImGui::InputTextWithHint("Transcripts folder", "(default) Documents\\Argos\\transcripts",
                                     &cfg.tools.transcript_dir))
            dirty = true;

        ImGui::Spacing();
        ImGui::Spacing();
    }
    ImGui::EndChild();

    if (dirty) application.settings_dirty() = true;

    ImGui::Separator();
    if (ImGui::Button("Save settings", ImVec2(140, 0))) {
        application.apply_settings_from_ui();
        application.save_settings();
        application.toast("Settings saved");
    }
    ImGui::SameLine();
    if (ImGui::Button("Open config folder", ImVec2(180, 0))) {
        const auto dir = win::app_data_dir().wstring();
        ::ShellExecuteW(nullptr, L"open", dir.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
    }
    ImGui::SameLine();
    ImGui::TextDisabled("config.json lives in %%APPDATA%%\\ArgosDesktop (outside the repo)");
}

void draw_log_tab() {
    const auto lines = log::recent(400);
    ImGui::TextDisabled("Log file: %s", win::to_utf8((win::app_data_dir() / L"logs").wstring()).c_str());
    ImGui::Separator();
    ImGui::BeginChild("log_scroll", ImVec2(0, 0), ImGuiChildFlags_None,
                      ImGuiWindowFlags_HorizontalScrollbar);
    for (const auto& line : lines) {
        ImVec4 color(0.82f, 0.84f, 0.86f, 1.0f);
        if (line.find("[ERROR]") != std::string::npos) color = ImVec4(1.0f, 0.45f, 0.40f, 1.0f);
        else if (line.find("[WARN ]") != std::string::npos) color = ImVec4(1.0f, 0.80f, 0.35f, 1.0f);
        else if (line.find("[DEBUG]") != std::string::npos) color = ImVec4(0.55f, 0.58f, 0.62f, 1.0f);
        ImGui::TextColored(color, "%s", line.c_str());
    }
    if (ImGui::GetScrollY() >= ImGui::GetScrollMaxY() - 4.0f) ImGui::SetScrollHereY(1.0f);
    ImGui::EndChild();
}

// ── QR pairing code ─────────────────────────────────────────────────────────
// Encodes the pairing JSON and draws it with ImDrawList — quiet zone included,
// white background, high contrast so the phone camera locks on instantly.
void draw_qr_code(const std::string& payload, float target_px) {
    if (payload.empty()) return;
    auto qr = qrcodegen::QrCode::encodeText(payload.c_str(),
                                          qrcodegen::QrCode::Ecc::MEDIUM);
    const int modules = qr.getSize();
    const int quiet = 4;
    const float cell = target_px / (float)(modules + quiet * 2);
    if (cell < 1.0f) return;

    ImVec2 pos = ImGui::GetCursorScreenPos();
    const float total = cell * (modules + quiet * 2);
    ImDrawList* dl = ImGui::GetWindowDrawList();
    dl->AddRectFilled(pos, ImVec2(pos.x + total, pos.y + total),
                      IM_COL32(255, 255, 255, 255), 6.0f);
    for (int y = 0; y < modules; ++y) {
        for (int x = 0; x < modules; ++x) {
            if (!qr.getModule(x, y)) continue;
            float x0 = pos.x + (x + quiet) * cell;
            float y0 = pos.y + (y + quiet) * cell;
            dl->AddRectFilled(ImVec2(x0, y0), ImVec2(x0 + cell, y0 + cell),
                              IM_COL32(12, 14, 20, 255));
        }
    }
    ImGui::Dummy(ImVec2(total, total));  // reserve the space we painted over
}

}  // namespace

// --- Minimal markdown renderer for assistant replies ------------------------
// Handles: fenced code blocks, # headers, -/* bullets, numbered lists,
// > quotes, --- rules, **bold**, *italic*, `inline code`, ~~strike~~.
// Renders word-by-word so styled segments still wrap correctly.

struct MdRun {
    std::string text;
    ImFont* font = nullptr;
    ImVec4 color{1, 1, 1, 1};
};

void md_emit_words(const std::vector<MdRun>& runs, float wrap_x) {
    bool first = true;
    const float line_start_x = ImGui::GetCursorPosX();
    for (const auto& r : runs) {
        size_t pos = 0;
        while (pos < r.text.size()) {
            size_t end = r.text.find(' ', pos);
            std::string word = r.text.substr(
                pos, end == std::string::npos ? std::string::npos : end - pos + 1);
            pos = (end == std::string::npos) ? r.text.size() : end + 1;
            if (word.empty()) continue;
            const float w = ImGui::CalcTextSize(word.c_str()).x;
            if (!first && ImGui::GetCursorPosX() + w > wrap_x) {
                ImGui::NewLine();
                ImGui::SetCursorPosX(line_start_x);
            }
            if (r.font) ImGui::PushFont(r.font);
            ImGui::PushStyleColor(ImGuiCol_Text, r.color);
            ImGui::TextUnformatted(word.c_str(),
                                   word.c_str() + word.size());
            ImGui::PopStyleColor();
            if (r.font) ImGui::PopFont();
            ImGui::SameLine(0, 0);
            first = false;
        }
    }
    ImGui::NewLine();
}

void md_render_line(std::string_view line, const ImVec4& base) {
    const ImVec4 italic{base.x * 0.92f, base.y * 0.92f, base.z * 0.92f, base.w};
    const ImVec4 code_col{0.95f, 0.78f, 0.45f, 1.0f};
    const ImVec4 strike{base.x, base.y, base.z, base.w * 0.55f};
    ImFont* bold = ui::font_bold();
    ImFont* mono = ui::font_mono();

    std::vector<MdRun> runs;
    std::string cur;
    ImFont* cur_font = nullptr;
    ImVec4 cur_col = base;
    bool in_bold = false, in_code = false, in_strike = false;

    auto flush = [&] {
        if (!cur.empty()) {
            runs.push_back({cur, cur_font, cur_col});
            cur.clear();
        }
    };
    size_t i = 0;
    while (i < line.size()) {
        if (line.substr(i, 2) == "**" && !in_code) {
            flush();
            in_bold = !in_bold;
            cur_font = (in_bold ? bold : nullptr);
            i += 2;
        } else if (line.substr(i, 2) == "~~" && !in_code) {
            flush();
            in_strike = !in_strike;
            cur_col = in_strike ? strike : base;
            i += 2;
        } else if (line[i] == '`') {
            flush();
            in_code = !in_code;
            cur_font = in_code ? mono : (in_bold ? bold : nullptr);
            cur_col = in_code ? code_col : base;
            ++i;
        } else if (line[i] == '*' && !in_code) {
            flush();  // single * → soft emphasis via slightly dimmer text
            cur_col = (cur_col.x == italic.x) ? base : italic;
            ++i;
        } else {
            cur += line[i++];
        }
    }
    flush();
    md_emit_words(runs, ImGui::GetWindowContentRegionMax().x);
}

void render_markdown(const std::string& md, const ImVec4& base) {
    const ImVec4 head_col{0.45f, 0.95f, 0.85f, 1.0f};
    const ImVec4 code_col{0.95f, 0.78f, 0.45f, 1.0f};
    const ImVec4 quote_col{0.62f, 0.68f, 0.72f, 1.0f};
    bool in_code = false;

    std::istringstream ss(md);
    std::string line;
    while (std::getline(ss, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.rfind("```", 0) == 0) {
            in_code = !in_code;
            continue;
        }
        if (in_code) {
            if (ui::font_mono()) ImGui::PushFont(ui::font_mono());
            ImGui::Indent(10);
            ImGui::PushStyleColor(ImGuiCol_Text, code_col);
            ImGui::TextUnformatted(line.empty() ? " " : line.c_str());
            ImGui::PopStyleColor();
            ImGui::Unindent(10);
            if (ui::font_mono()) ImGui::PopFont();
            continue;
        }
        if (line.empty()) {
            ImGui::Spacing();
            continue;
        }
        // horizontal rule
        if (line == "---" || line == "***" || line == "___") {
            ImGui::Separator();
            continue;
        }
        // headers
        int h = 0;
        while (h < (int)line.size() && line[h] == '#') ++h;
        if (h > 0 && h <= 6 && h < (int)line.size() && line[h] == ' ') {
            ImFont* f = ui::font_header() ? ui::font_header() : ui::font_bold();
            if (f) ImGui::PushFont(f);
            md_render_line(std::string_view(line).substr(h + 1), head_col);
            if (f) ImGui::PopFont();
            continue;
        }
        // bullets
        if ((line.rfind("- ", 0) == 0 || line.rfind("* ", 0) == 0 ||
             line.rfind("+ ", 0) == 0)) {
            ImGui::Indent(14);
            ImGui::TextUnformatted("\xE2\x80\xA2 ");  // "• "
            ImGui::SameLine(0, 0);
            md_render_line(std::string_view(line).substr(2), base);
            ImGui::Unindent(14);
            continue;
        }
        // numbered lists — keep the number, just indent
        {
            size_t d = 0;
            while (d < line.size() && isdigit((unsigned char)line[d])) ++d;
            if (d > 0 && d < 4 && d + 1 < line.size() &&
                (line[d] == '.' || line[d] == ')') && line[d + 1] == ' ') {
                ImGui::Indent(14);
                md_render_line(line, base);
                ImGui::Unindent(14);
                continue;
            }
        }
        // quotes
        if (line.rfind("> ", 0) == 0) {
            ImGui::Indent(14);
            md_render_line(std::string_view(line).substr(2), quote_col);
            ImGui::Unindent(14);
            continue;
        }
        md_render_line(line, base);
    }
}

// --- end markdown renderer ---------------------------------------------------

// Terminal-style slash commands — typed into the chat box, executed locally
// (no model round-trip). This is where the quick controls live; the Settings
// tab remains for the longer configuration surface.
void run_slash_command(App& application, const std::string& line) {
    auto& agent = application.agent();
    const auto sp = line.find(' ');
    std::string cmd = line.substr(
        1, sp == std::string::npos ? std::string::npos : sp - 1);
    std::string rest =
        sp == std::string::npos ? "" : line.substr(sp + 1);
    std::transform(cmd.begin(), cmd.end(), cmd.begin(),
                   [](unsigned char c) { return std::tolower(c); });

    auto run_tool = [&agent](const char* tool, nlohmann::json args) {
        std::thread([&agent, t = std::string(tool), args = std::move(args)] {
            std::string out;
            try {
                auto r = tools::execute(t, args);
                out = "/" + t + " → " + (r.ok ? r.output : "error: " + r.output);
            } catch (const std::exception& e) {
                out = "/" + t + " → error: " + e.what();
            } catch (...) {
                out = "/" + t + " → error: unknown exception";
            }
            agent.post_visible("tool", out);
        }).detach();
    };

    if (cmd == "help") {
        agent.post_visible(
            "tool",
            "slash commands:\n"
            "  /ides            fleet status — every IDE + terminal\n"
            "  /broadcast <msg> send a task to ALL IDEs and terminals\n"
            "  /onboard [path]  repo recon shown here (nothing goes to the IDE)\n"
            "  /summary [path]  glass onboarding summary popup\n"
            "  /scan [path]     scan repo for leaked API keys\n"
            "  /commit [msg]    stage + commit the workspace\n"
            "  /backend         backend provider health\n"
            "  /park            pin/release the robot\n"
            "  /clear           wipe chat history\n"
            "anything else — natural language goes to the model");
    } else if (cmd == "clear") {
        agent.clear();
    } else if (cmd == "park") {
        application.robot().toggle_parked();
    } else if (cmd == "ides" || cmd == "status") {
        run_tool("ide_status", {});
    } else if (cmd == "backend") {
        run_tool("backend_status", {});
    } else if (cmd == "scan") {
        run_tool("secret_scan", rest.empty() ? nlohmann::json::object()
                                             : nlohmann::json{{"path", rest}});
    } else if (cmd == "commit") {
        run_tool("git_commit", rest.empty() ? nlohmann::json::object()
                                            : nlohmann::json{{"message", rest}});
    } else if (cmd == "onboard") {
        run_tool("onboard_project", {{"send_to_ide", false}});
    } else if (cmd == "summary") {
        run_tool("show_onboarding_summary", {});
    } else if (cmd == "broadcast") {
        if (rest.empty())
            agent.post_visible("error", "/broadcast needs a message");
        else
            run_tool("ide_dispatch",
                     {{"tasks", nlohmann::json::array(
                                    {{{"target", "all"}, {"text", rest}}})}});
    } else {
        agent.post_visible("error", "unknown /" + cmd + " — try /help");
    }
}

void draw_chat_tab(App& application) {
    auto& agent = application.agent();
    if (!agent.ready()) {
        ImGui::TextDisabled("Backend not connected — enable it in Settings to chat.");
        return;
    }

    // Terminal surface: dark shell background, mono font, PS-style prompts.
    ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0.02f, 0.045f, 0.055f, 1.0f));
    if (ui::font_mono()) ImGui::PushFont(ui::font_mono());
    ImGui::BeginChild("chat_scroll", ImVec2(0, -126),
                      ImGuiChildFlags_Border);
    for (const auto& e : agent.history()) {
        if (e.role == "user") {
            ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(0.45f, 0.95f, 0.55f, 1.0f));
            ImGui::TextWrapped("C:\\argos> %s", e.text.c_str());
        } else if (e.role == "assistant") {
            ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(0.35f, 0.90f, 0.60f, 1.0f));
            ImGui::TextUnformatted("argos>");
            render_markdown(e.text, ImVec4(0.80f, 0.95f, 0.85f, 1.0f));
        } else if (e.role == "tool") {
            ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(0.55f, 0.60f, 0.55f, 1.0f));
            ImGui::TextWrapped("  · %s", e.text.c_str());
        } else {
            ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(1.0f, 0.45f, 0.45f, 1.0f));
            ImGui::TextWrapped("%s", e.text.c_str());
        }
        ImGui::PopStyleColor();
    }
    if (agent.busy()) ImGui::TextDisabled("argos> working…");
    // Keep pinned to the newest entry while the agent streams activity.
    if (ImGui::GetScrollY() >= ImGui::GetScrollMaxY() - 40)
        ImGui::SetScrollHereY(1.0f);
    ImGui::EndChild();
    if (ui::font_mono()) ImGui::PopFont();
    ImGui::PopStyleColor();

    // Follow-up suggestions — four tappable prompts refreshed after each turn
    // so the user can fire the obvious next request without typing.
    const auto sugs = agent.suggestions();
    if (!sugs.empty() && !agent.busy()) {
        const float bw = (ImGui::GetContentRegionAvail().x - 6.0f) / 2.0f;
        for (size_t i = 0; i < sugs.size() && i < 4; ++i) {
            if (i % 2) ImGui::SameLine();
            std::string label = sugs[i];
            if (label.size() > 40) label = label.substr(0, 37) + "...";
            label += "##sug" + std::to_string(i);
            if (ImGui::Button(label.c_str(), ImVec2(bw, 0)))
                agent.ask_async(sugs[i]);
        }
        ImGui::Spacing();
    }

    // Fleet strip — connected IDEs + terminal windows, refreshed ~1/s so the
    // user always sees what Argos can reach right now.
    static nlohmann::json fleet = {{"ides", nlohmann::json::array()},
                                   {"terminals", nlohmann::json::array()}};
    static double fleet_at = 0.0;
    if (ImGui::GetTime() - fleet_at > 1.0) {
        fleet_at = ImGui::GetTime();
        try {
            fleet = tools::fleet_summary();
        } catch (const std::exception& e) {
            fleet = {{"ides", nlohmann::json::array()},
                     {"terminals", nlohmann::json::array()},
                     {"error", e.what()}};
        } catch (...) {
            fleet = {{"ides", nlohmann::json::array()},
                     {"terminals", nlohmann::json::array()},
                     {"error", "fleet scan failed"}};
        }
    }
    {
        std::ostringstream line;
        line << "net>";
        static const nlohmann::json kEmpty = nlohmann::json::array();
        const auto& ides =
            fleet.is_object() ? fleet.value("ides", kEmpty) : kEmpty;
        const auto& terms =
            fleet.is_object() ? fleet.value("terminals", kEmpty) : kEmpty;
        const auto& clis =
            fleet.is_object() ? fleet.value("clis", kEmpty) : kEmpty;
        if (ides.empty() && terms.empty() && clis.empty()) {
            line << " no IDEs or terminals connected";
        } else {
            for (const auto& e : ides)
                line << "  [" << e.value("name", "?") << "]";
            for (const auto& t : terms)
                line << "  [term: " << t.value("title", "?") << "]";
            for (const auto& c : clis) {
                if (c.value("running", false))
                    line << "  [cli: " << c.value("name", "?") << "]";
            }
            for (const auto& c : clis) {
                if (!c.value("running", false) && c.value("installed", false) &&
                    c.value("headline", false))
                    line << "  [cli: " << c.value("bin", "?") << " installed]";
            }
        }
        ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(0.30f, 0.65f, 0.75f, 1.0f));
        if (ui::font_mono()) ImGui::PushFont(ui::font_mono());
        ImGui::TextWrapped("%s", line.str().c_str());
        if (ui::font_mono()) ImGui::PopFont();
        ImGui::PopStyleColor();
    }

    static std::string input;
    ImGui::SetNextItemWidth(-90);
    bool send = ImGui::InputTextWithHint(
        "##chat_input",
        agent.busy() ? "working…" : "argos> type a message or /help",
        &input,
        ImGuiInputTextFlags_EnterReturnsTrue);
    ImGui::SameLine();
    send |= ImGui::Button("Send", ImVec2(0, 0)) && !input.empty();
    ImGui::SameLine();
    if (ImGui::SmallButton("Clear")) {
        agent.clear();
        input.clear();
    }
    if (send && !input.empty()) {
        if (input.front() == '/') {
            run_slash_command(application, input);
            input.clear();
        } else if (!agent.busy() && agent.ask_async(input)) {
            input.clear();
        }
    }
}

void draw_transcript_tab(App& application) {
    (void)application;  // intentionally unused — tab shows static placeholder text
    ImGui::TextDisabled("Live transcription appears here while listening.");
    ImGui::Separator();
    ImGui::BeginChild("transcript_scroll", ImVec2(0, -40));
    ImGui::TextWrapped("Not listening.");
    ImGui::EndChild();
}

void draw_tools_tab(App& application) {
    static const char* const kProviders[] = {"auto",     "copilot", "continue", "cline",
                                             "roo",      "cody",    "generic"};
    static int provider_idx = 0;
    static std::string prompt;
    static std::string last_result;
    static std::future<std::string> pending;
    static auto last_probe = std::chrono::steady_clock::time_point{};

    auto& ide = application.ide();

    const bool busy = pending.valid() &&
                      pending.wait_for(std::chrono::seconds(0)) != std::future_status::ready;
    if (pending.valid() && !busy) {
        last_result = pending.get();
    }

    ImGui::SeparatorText("IDE bridge (VS Code / Cursor / Windsurf)");
    help_marker("Install the extension in argos-desktop/extension-ide. It listens on "
                "127.0.0.1 only and Argos finds it through "
                "%APPDATA%\\ArgosDesktop\\ide-bridge.json.");

    // Re-probe at most every 5 seconds — a dead bridge fails fast on loopback.
    const auto now = std::chrono::steady_clock::now();
    if (now - last_probe > std::chrono::seconds(5)) {
        last_probe = now;
        ide.refresh();
    }

    if (ide.connected()) {
        ImGui::TextColored(ImVec4(0.20f, 0.90f, 0.55f, 1.0f), "\xe2\x97\x8f");
        ImGui::SameLine();
        ImGui::Text("%s  ·  port %d", ide.endpoint().ide.c_str(), ide.endpoint().port);
        if (!ide.endpoint().workspace.empty()) {
            ImGui::SameLine();
            ImGui::TextDisabled("  ·  %s", ide.endpoint().workspace.c_str());
        }
    } else {
        ImGui::TextColored(ImVec4(0.95f, 0.45f, 0.30f, 1.0f), "\xe2\x97\x8b");
        ImGui::SameLine();
        ImGui::TextDisabled("no IDE bridge found — open the editor with the Argos Bridge extension");
    }

    ImGui::SameLine();
    if (ImGui::SmallButton("Reconnect")) {
        ide.refresh();
    }
    if (ide.connected()) {
        ImGui::SameLine();
        if (ImGui::SmallButton("Read active file") && !busy) {
            pending = std::async(std::launch::async, [&application]() -> std::string {
                auto status = application.ide().call("ide.status");
                if (!status) return "error: " + application.ide().last_error();
                const std::string active = status->value("activeFile", "");
                if (active.empty()) return "no file is active in the editor";
                auto r = tools::execute("read_file", {{"path", active}, {"limit", 12}});
                if (!r.ok) return "read_file failed: " + r.output;
                std::string head = r.output;
                if (head.size() > 700) head = head.substr(0, 700) + "…";
                return head;
            });
        }
        ImGui::SameLine();
        ImGui::TextDisabled("(tools::execute → bridge)");
    }

    ImGui::SetNextItemWidth(160);
    ImGui::Combo("AI chat provider", &provider_idx, kProviders, IM_ARRAYSIZE(kProviders));
    ImGui::SameLine();
    help_marker("auto = first detected assistant. Webview chats (Continue, Cline, "
                "Cody) get the prompt via clipboard paste + Enter.");

    ImGui::SetNextItemWidth(-150);
    ImGui::InputTextWithHint("##ide_prompt", "prompt for the IDE's AI chat…", &prompt);
    ImGui::SameLine();

    if (busy) {
        ImGui::BeginDisabled();
        ImGui::Button("Send to chat", ImVec2(140, 0));
        ImGui::EndDisabled();
    } else if (ImGui::Button("Send to chat", ImVec2(140, 0))) {
        if (!ide.connected() || prompt.empty()) {
            last_result = ide.connected() ? "type a prompt first" : "bridge not connected";
        } else {
            const std::string text = prompt;
            const std::string provider = kProviders[provider_idx];
            pending = std::async(std::launch::async, [&application, text, provider]() -> std::string {
                auto result =
                    application.ide().call("chat.send", {{"text", text},
                                                         {"provider", provider},
                                                         {"submit", true}});
                if (!result) return "error: " + application.ide().last_error();
                const std::string target = result->value("provider", provider);
                if (result->value("delivered", "") == "needs-paste") {
                    // Chat input is focused inside the IDE and the prompt is on
                    // the clipboard — raise the IDE window, then finish with
                    // synthetic Ctrl+V / Enter. NEVER paste unless the IDE
                    // actually took the foreground (else we'd type into a
                    // random focused app).
                    if (!win::bring_process_to_front(
                            (DWORD)application.ide().endpoint().main_pid)) {
                        return "IDE window did not take focus — prompt left on clipboard";
                    }
                    ::Sleep(450);
                    win::send_paste_enter();
                    return "pasted into " + target + " chat";
                }
                return "delivered to " + target + " chat";
            });
        }
    }

    if (!last_result.empty()) {
        ImGui::TextDisabled("last: %s", last_result.c_str());
    }

    ImGui::Spacing();
    ImGui::SeparatorText("Onboarding");
    help_marker("Runs the same show_onboarding_summary tool the agent uses — "
                "opens the glass report built from ONBOARDING.md.");
    if (ImGui::Button("Onboarding summary", ImVec2(160, 0))) {
        pending = std::async(std::launch::async, []() -> std::string {
            auto r = tools::execute("show_onboarding_summary", {{"path", ""}});
            return r.ok ? r.output : "error: " + r.output;
        });
    }
    ImGui::SameLine();
    if (ImGui::Button("Onboard this repo", ImVec2(160, 0))) {
        pending = std::async(std::launch::async, []() -> std::string {
            auto r = tools::execute("onboard_project", {{"path", ""}});
            return r.ok ? r.output : "error: " + r.output;
        });
    }

    ImGui::Spacing();
    ImGui::SeparatorText("Tool activity");
    ImGui::TextWrapped("No tool activity yet.");
}

void draw_phone_tab(App& application) {
    auto& srv = phone::phone_server();
    auto& link = link::link_client();
    Config& cfg = config();
    static int pair_mode = 0;  // 0 = backend relay, 1 = direct LAN

    ImGui::SeparatorText("Pair the Android Argos app");
    help_marker("Show this QR to the Argos app on your phone. Backend relay "
                "routes commands through the Argos backend; Direct LAN keeps "
                "everything on this network. Treat the QR like a password — "
                "it carries a credential.");

    ImGui::SetNextItemWidth(220);
    ImGui::Combo("Pairing transport", &pair_mode, "Backend relay\0Direct LAN\0");

    if (pair_mode == 0) {
        // ── Backend relay ──
        bool enabled = cfg.link.enabled;
        if (ImGui::Checkbox("Backend link", &enabled)) {
            cfg.link.enabled = enabled;
            config_save();
            if (enabled) link.start();
            else link.stop();
        }
        ImGui::SameLine();
        if (link.registered()) {
            ImGui::TextColored(ImVec4(0.20f, 0.90f, 0.55f, 1.0f), "\xe2\x97\x8f");
            ImGui::SameLine();
            ImGui::Text("%s", link.status_line().c_str());
        } else {
            ImGui::TextColored(ImVec4(0.95f, 0.45f, 0.30f, 1.0f), "\xe2\x97\x8b");
            ImGui::SameLine();
            ImGui::TextDisabled("%s", link.status_line().c_str());
        }

        std::string payload = link.qr_payload();
        if (!payload.empty()) {
            ImGui::TextDisabled("phone connects via: %s", cfg.link.base_url.c_str());
            ImGui::Spacing();
            draw_qr_code(payload, 300.0f);
            ImGui::Spacing();
            if (ImGui::Button("Copy pairing JSON")) {
                ImGui::SetClipboardText(payload.c_str());
                application.toast("Pairing JSON copied — treat it like a password");
            }
            ImGui::SameLine();
            if (ImGui::Button("Revoke phones")) {
                if (link.revoke_phones())
                    application.toast("All phones unpaired — new QR generated");
                else
                    application.toast("Revoke failed — backend unreachable");
            }
            help_marker("Revokes every paired phone and rotates the pair code. "
                        "The QR updates immediately; rescan to re-pair.");
        } else {
            ImGui::TextWrapped(
                "Waiting for the backend at %s — the QR appears once the "
                "desktop is registered.",
                cfg.link.base_url.c_str());
        }
    } else {
        // ── Direct LAN ──
        bool enabled = cfg.phone.enabled;
        if (ImGui::Checkbox("Phone link server", &enabled)) {
            cfg.phone.enabled = enabled;
            config_save();
            if (enabled) srv.start();
            else srv.stop();
        }
        ImGui::SameLine();
        if (srv.running()) {
            ImGui::TextColored(ImVec4(0.20f, 0.90f, 0.55f, 1.0f), "\xe2\x97\x8f");
            ImGui::SameLine();
            ImGui::Text("listening on port %d", srv.port());
        } else {
            ImGui::TextColored(ImVec4(0.95f, 0.45f, 0.30f, 1.0f), "\xe2\x97\x8b");
            ImGui::SameLine();
            ImGui::TextDisabled(cfg.phone.enabled ? "not running" : "disabled");
        }

        if (srv.running()) {
            const auto ips = srv.lan_ips();
            if (ips.empty()) {
                ImGui::TextColored(ImVec4(0.95f, 0.70f, 0.30f, 1.0f),
                                   "No LAN address found — is the PC on the network?");
            } else {
                ImGui::TextDisabled("phone connects to:");
                for (const auto& ip : ips) {
                    ImGui::SameLine(0, 12);
                    ImGui::Text("http://%s:%d", ip.c_str(), srv.port());
                }
            }
            ImGui::Spacing();
            draw_qr_code(srv.qr_payload(), 300.0f);
            ImGui::Spacing();
            if (ImGui::Button("Copy pairing JSON")) {
                ImGui::SetClipboardText(srv.qr_payload().c_str());
                application.toast("Pairing JSON copied — treat it like a password");
            }
            ImGui::SameLine();
            if (ImGui::Button("Regenerate token")) {
                cfg.phone.token.clear();
                srv.stop();
                srv.start();
                config_save();
                application.toast("New token — previously paired phones are revoked");
            }
        } else if (cfg.phone.enabled) {
            if (ImGui::Button("Start server")) srv.start();
        }
    }

    ImGui::Spacing();
    ImGui::SeparatorText("Phone activity");
    auto events = srv.recent_events();
    if (events.empty()) {
        ImGui::TextDisabled("No phone commands yet.");
    } else {
        ImGui::BeginChild("phone_events", ImVec2(0, -40), ImGuiChildFlags_None,
                          ImGuiWindowFlags_HorizontalScrollbar);
        for (auto it = events.rbegin(); it != events.rend(); ++it) {
            ImVec4 color = it->ok ? ImVec4(0.82f, 0.84f, 0.86f, 1.0f)
                                  : ImVec4(1.0f, 0.45f, 0.40f, 1.0f);
            ImGui::TextColored(color, "%s  %-16s  %s%s", it->when.c_str(),
                               it->ip.c_str(), it->method.c_str(),
                               it->ok ? "" : "  (rejected)");
        }
        ImGui::EndChild();
    }
}

void App::draw_ui() {
    // Full-window dock-less layout: one big child per tab.
    const ImGuiViewport* viewport = ImGui::GetMainViewport();
    ImGui::SetNextWindowPos(viewport->WorkPos);
    ImGui::SetNextWindowSize(viewport->WorkSize);
    ImGui::Begin("Argos", nullptr,
                 ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
                     ImGuiWindowFlags_NoBringToFrontOnFocus | ImGuiWindowFlags_NoNavFocus |
                     ImGuiWindowFlags_NoSavedSettings);

    ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(0.00f, 0.90f, 0.80f, 1.0f));
    ImGui::Text("ARGOS");
    ImGui::PopStyleColor();
    ImGui::SameLine();
    ImGui::TextDisabled("desktop companion  ·  v" ARGOS_VERSION "  ·  local only");
    ImGui::Separator();
    draw_status_bar();
    ImGui::Spacing();

    if (ImGui::BeginTabBar("main_tabs")) {
        if (ImGui::BeginTabItem("Chat")) {
            draw_chat_tab(*this);
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Transcript")) {
            draw_transcript_tab(*this);
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Settings")) {
            draw_settings_tab(*this);
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Tools")) {
            draw_tools_tab(*this);
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Phone")) {
            draw_phone_tab(*this);
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Log")) {
            draw_log_tab();
            ImGui::EndTabItem();
        }
        ImGui::EndTabBar();
    }

    // Transient toast in the corner.
    if (!toast_message_.empty()) {
        const auto now = std::chrono::duration<double>(
                             std::chrono::steady_clock::now().time_since_epoch())
                             .count();
        if (now < toast_until_) {
            ImGui::SetNextWindowPos(ImVec2(viewport->WorkPos.x + viewport->WorkSize.x - 340,
                                           viewport->WorkPos.y + viewport->WorkSize.y - 70));
            ImGui::SetNextWindowSize(ImVec2(320, 0));
            ImGui::Begin("##toast", nullptr,
                         ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoInputs |
                             ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoSavedSettings);
            ImGui::TextWrapped("%s", toast_message_.c_str());
            ImGui::End();
        }
    }

    ImGui::End();
}

}  // namespace argos
