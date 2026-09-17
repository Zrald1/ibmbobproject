#include "core/app.h"

#include <imgui.h>

#include <chrono>
#include <cstdio>

#include "core/config.h"
#include "core/log.h"
#include "link/link_client.h"
#include "phone/phone_server.h"
#include "platform/win_util.h"

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
    std::snprintf(api_key_input_, sizeof(api_key_input_), "%s", config().cerebras.api_key.c_str());
    std::snprintf(aai_key_input_, sizeof(aai_key_input_), "%s", config().assemblyai.api_key.c_str());

    // ── Floating robot (Three.js scene in WebView2) ──
    if (config().overlay.enabled) {
        if (robot_.create(hinst)) {
            robot_.set_tap_handler([this] {
                toast("Robot tapped — the voice pipeline lands with the AssemblyAI service.");
            });
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
    config().cerebras.api_key = api_key_input_;
    config().assemblyai.api_key = aai_key_input_;
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

        robot_.tick();

        if (!panel_.render_frame({})) {
            running_ = false;
        }
    }

    link::link_client().stop();
    phone::phone_server().stop();
    robot_.destroy();
    panel_.destroy();
    log::info("Argos Desktop shutting down");
    return 0;
}

}  // namespace argos
