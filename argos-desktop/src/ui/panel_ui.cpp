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
#include <string>

#include "core/app.h"
#include "core/config.h"
#include "core/log.h"
#include "link/link_client.h"
#include "phone/phone_server.h"
#include "platform/win_util.h"
#include "tools/tools.h"
#include "vendor/qrcodegen.hpp"

namespace argos {
namespace {

const char* const kChatModels[] = {"gpt-oss-120b", "zai-glm-4.7", "qwen-3.8-27b"};
const char* const kVisionModels[] = {"qwen-3.8-27b"};
const char* const kSpeechModels[] = {"universal-3-5-pro", "universal-streaming-english",
                                     "universal-streaming-multilingual"};
const char* const kAudioSources[] = {"microphone", "system", "both"};
const char* const kModes[] = {"(server default)", "balanced", "min_latency", "max_accuracy"};

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
    const bool cerebras_ok = !cfg.cerebras.api_key.empty();
    const bool assembly_ok = !cfg.assemblyai.api_key.empty();

    auto dot = [](bool ok, const char* label) {
        ImGui::TextColored(ok ? ImVec4(0.20f, 0.90f, 0.55f, 1.0f) : ImVec4(0.95f, 0.45f, 0.30f, 1.0f),
                           ok ? "\xe2\x97\x8f" : "\xe2\x97\x8b");
        ImGui::SameLine();
        ImGui::TextUnformatted(label);
    };

    dot(cerebras_ok, "Cerebras");
    ImGui::SameLine(0, 18);
    dot(assembly_ok, "AssemblyAI");
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
        // ── Cerebras ──
        ImGui::SeparatorText("Cerebras (chat + vision)");
        ImGui::TextUnformatted("API key");
        help_marker("Stored encrypted with Windows DPAPI in %APPDATA%\\ArgosDesktop\\config.json — "
                    "never in the project folder, never in git.");
        if (secret_input("cerebras_key", application.cerebras_key_buffer(),
                         App::key_buffer_size, application.reveal_cerebras_key())) {
            dirty = true;
        }
        ImGui::SetItemTooltip("Get a key at cloud.cerebras.ai");

        ImGui::SetNextItemWidth(320);
        if (ImGui::InputText("Base URL", &cfg.cerebras.base_url)) dirty = true;
        ImGui::SetNextItemWidth(320);
        if (combo_from_list("Chat model", cfg.cerebras.model, kChatModels,
                            IM_ARRAYSIZE(kChatModels)))
            dirty = true;
        ImGui::SameLine();
        help_marker("gpt-oss-120b is the fastest general model; zai-glm-4.7 is stronger; "
                    "qwen-3.8-27b is the one that can see images.");
        ImGui::SetNextItemWidth(320);
        if (combo_from_list("Vision model", cfg.cerebras.vision_model, kVisionModels,
                            IM_ARRAYSIZE(kVisionModels)))
            dirty = true;
        ImGui::SameLine();
        help_marker("Used for \"what's on my screen?\". qwen-3.8-27b accepts images on the "
                    "public shared tier.");

        ImGui::SetNextItemWidth(160);
        if (ImGui::SliderInt("Max tokens", &cfg.cerebras.max_tokens, 60, 2000)) dirty = true;
        ImGui::SetNextItemWidth(160);
        float temperature = static_cast<float>(cfg.cerebras.temperature);
        if (ImGui::SliderFloat("Temperature", &temperature, 0.0f, 1.5f, "%.2f")) {
            cfg.cerebras.temperature = temperature;
            dirty = true;
        }
        ImGui::SetNextItemWidth(160);
        if (ImGui::InputInt("Timeout (s)", &cfg.cerebras.timeout_seconds)) dirty = true;

        ImGui::Spacing();
        ImGui::SeparatorText("AssemblyAI (live transcription)");
        ImGui::TextUnformatted("API key");
        help_marker("Streaming keys are sent only over TLS to streaming.assemblyai.com.");
        if (secret_input("aai_key", application.assemblyai_key_buffer(),
                         App::key_buffer_size, application.reveal_assemblyai_key())) {
            dirty = true;
        }

        ImGui::SetNextItemWidth(320);
        if (ImGui::InputText("WebSocket URL", &cfg.assemblyai.ws_url)) dirty = true;
        ImGui::SetNextItemWidth(320);
        if (combo_from_list("Speech model", cfg.assemblyai.speech_model, kSpeechModels,
                            IM_ARRAYSIZE(kSpeechModels)))
            dirty = true;
        ImGui::SameLine();
        help_marker("universal-3-5-pro = best accuracy and turn detection. "
                    "universal-streaming-english is the cheapest option.");
        ImGui::SetNextItemWidth(320);
        if (ImGui::InputTextWithHint("Language codes", "[\"en\"]", &cfg.assemblyai.language_codes))
            dirty = true;
        ImGui::SameLine();
        help_marker("Optional JSON list, e.g. [\"en\", \"es\"]. Leave empty to let the model "
                    "code-switch on its own.");
        ImGui::SetNextItemWidth(200);
        if (combo_from_list("Mode", cfg.assemblyai.mode, kModes, IM_ARRAYSIZE(kModes))) dirty = true;
        if (cfg.assemblyai.mode == "(server default)") cfg.assemblyai.mode.clear();

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

void draw_chat_tab(App& application) {
    ImGui::TextDisabled("Chat history appears here once the Cerebras service is online.");
    ImGui::Separator();
    ImGui::BeginChild("chat_scroll", ImVec2(0, -40));
    ImGui::TextWrapped("No conversation yet.");
    ImGui::EndChild();
}

void draw_transcript_tab(App& application) {
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
