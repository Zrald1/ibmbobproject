#include "core/app.h"

#include <imgui.h>

#include <chrono>
#include <cstdio>

#include "core/config.h"
#include "core/log.h"
#include "link/link_client.h"
#include "mcp/mcp_client.h"
#include "phone/phone_server.h"
#include "platform/win_util.h"
#include "voice/voice.h"

namespace {

// ── Caps Lock push-to-talk ────────────────────────────────────────────────
// A low-level keyboard hook watches VK_CAPITAL. Every press is swallowed and
// reinterpreted:
//   held ≥450 ms        → start listening (fires while the key is still down)
//   pressed while live  → stop listening and transcribe
//   short tap           → re-injected as a normal CapsLock keystroke, so the
//                         key keeps working for its original purpose.
HHOOK g_caps_hook = nullptr;
ULONGLONG g_caps_down_tick = 0;
bool g_caps_consumed = false;   // we ate a press — eat its release too
bool g_caps_triggered = false;  // the hold already fired start_listen()

LRESULT CALLBACK caps_hook_proc(int code, WPARAM wp, LPARAM lp) {
    if (code == HC_ACTION) {
        const auto* k = reinterpret_cast<KBDLLHOOKSTRUCT*>(lp);
        // Esc while Argos is speaking = cancel the voice reply. Swallowed so
        // it can't also close whatever panel the user has open.
        if (k->vkCode == VK_ESCAPE &&
            (wp == WM_KEYDOWN || wp == WM_SYSKEYDOWN) &&
            argos::voice::speaking()) {
            argos::voice::stop_speaking();
            return 1;
        }
        if (k->vkCode == VK_CAPITAL) {
            if (wp == WM_KEYDOWN || wp == WM_SYSKEYDOWN) {
                if (g_caps_consumed) return 1;  // auto-repeat while held
                if (argos::voice::listening() ||
                    argos::voice::transcribing()) {
                    argos::voice::stop_listen();  // press-again = cut the take
                    g_caps_consumed = true;
                    g_caps_triggered = true;      // don't re-inject on release
                    return 1;
                }
                g_caps_down_tick = ::GetTickCount64();
                g_caps_consumed = true;
                g_caps_triggered = false;
                return 1;
            }
            if ((wp == WM_KEYUP || wp == WM_SYSKEYUP) && g_caps_consumed) {
                const bool fired = g_caps_triggered;
                g_caps_consumed = false;
                g_caps_triggered = false;
                if (!fired) {
                    // Short tap that did nothing — give the OS its CapsLock.
                    INPUT in[2]{};
                    in[0].type = INPUT_KEYBOARD;
                    in[0].ki.wVk = VK_CAPITAL;
                    in[1] = in[0];
                    in[1].ki.dwFlags = KEYEVENTF_KEYUP;
                    ::SendInput(2, in, sizeof(INPUT));
                }
                return 1;
            }
        }
    }
    return ::CallNextHookEx(nullptr, code, wp, lp);
}

}  // namespace

namespace argos {

App& app() {
    static App instance;
    return instance;
}

bool App::init(HINSTANCE hinst) {
    instance_ = hinst;

    if (!gfx_.create()) return false;

    if (!panel_.create(gfx_, hinst, L"Argos Desktop")) return false;
    panel_.set_ui_callback([this] { draw_ui(); });

    // Seed the settings fields from the loaded config.
    std::snprintf(murf_key_input_, sizeof(murf_key_input_), "%s", config().murf.api_key.c_str());
    std::snprintf(sm_key_input_, sizeof(sm_key_input_), "%s", config().speechmatics.api_key.c_str());
    std::snprintf(backend_key_input_, sizeof(backend_key_input_), "%s", config().backend.api_key.c_str());

    // ── Floating robot (Three.js scene in WebView2) ──
    if (config().overlay.enabled) {
        if (robot_.create(hinst)) {
            robot_.set_tap_handler([this] { voice::toggle_listen(); });
            if (!summary_.create(hinst))
                log::warn("Onboarding summary overlay unavailable");
        } else {
            log::error("The robot overlay could not start; the control panel still works.");
            toast("Robot overlay unavailable — see the Log tab.");
        }
    }

    // ── Phone link (Android Argos pairing) ──
    // Direct-LAN server for same-network pairing…
    if (config().phone.enabled && !phone::phone_server().start()) {
        log::error("Phone link server could not start — LAN pairing is unavailable.");
    }
    // …and the backend relay for pairing through the Argos backend.
    if (config().link.enabled) link::link_client().start();

    // External MCP servers — connects in the background; tools appear in the
    // brain's schema list as each server finishes handshaking.
    if (!config().mcp.servers.empty()) mcp::client().connect_all();

    // Caps Lock push-to-talk (long-press = listen, press again = stop).
    g_caps_hook = ::SetWindowsHookExW(WH_KEYBOARD_LL, caps_hook_proc,
                                      instance_, 0);
    if (!g_caps_hook)
        log::warn("Caps Lock voice hotkey could not be installed");

    log::info("Argos Desktop " ARGOS_VERSION " initialised");
    return true;
}

void App::toast(std::string message, double seconds) {
    toast_message_ = std::move(message);
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    toast_until_ = std::chrono::duration<double>(now).count() + seconds;
}

bool App::keys_present() const {
    return !config().cerebras.api_key.empty() && !config().assemblyai.api_key.empty();
}

void App::save_settings() {
    if (config_save()) {
        log::info("Settings saved to " + win::to_utf8(config_path().wstring()));
    } else {
        log::error("Failed to save settings");
    }
}

void App::apply_settings_from_ui() {
    config().murf.api_key = murf_key_input_;
    config().speechmatics.api_key = sm_key_input_;
    config().backend.api_key = backend_key_input_;
}

int App::run() {
    MSG msg{};
    while (running_) {
        while (::PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
            if (msg.message == WM_QUIT) {
                running_ = false;
                break;
            }
            ::TranslateMessage(&msg);
            ::DispatchMessageW(&msg);
        }
        if (!running_) break;

        robot_.set_panel_hold(panel_.visible());
        robot_.tick();

        // Caps Lock long-press → start listening while the key is still down.
        if (g_caps_consumed && !g_caps_triggered && !voice::listening() &&
            !voice::transcribing() &&
            ::GetTickCount64() - g_caps_down_tick >= 450) {
            g_caps_triggered = true;
            voice::start_listen();
        }

        if (!panel_.render_frame({})) {
            running_ = false;
        }
    }

    if (g_caps_hook) {
        ::UnhookWindowsHookEx(g_caps_hook);
        g_caps_hook = nullptr;
    }
    voice::shutdown();
    mcp::client().shutdown();
    link::link_client().stop();
    phone::phone_server().stop();
    summary_.destroy();
    robot_.destroy();
    panel_.destroy();
    log::info("Argos Desktop shutting down");
    return 0;
}

}  // namespace argos
