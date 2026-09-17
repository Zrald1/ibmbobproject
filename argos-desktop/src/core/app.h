#pragma once

// Application shell: owns the graphics device, windows and the AI services,
// and drives the frame loop.

#include <windows.h>

#include <memory>
#include <string>

#include "bridge/ide_bridge.h"
#include "gfx/device.h"
#include "ui/panel_window.h"
#include "ui/robot_overlay.h"

namespace argos {

class App {
public:
    bool init(HINSTANCE hinst);
    int run();
    void request_exit() { running_ = false; }

    gfx::Device& gfx() { return gfx_; }
    ui::PanelWindow& panel() { return panel_; }
    ui::RobotOverlay& robot() { return robot_; }
    bridge::IdeBridge& ide() { return ide_; }
    HINSTANCE hinst() const { return instance_; }

    // ── UI state shared with ui/panel_ui.cpp ──
    bool keys_present() const;
    void save_settings();
    void apply_settings_from_ui();
    bool& settings_dirty() { return settings_dirty_; }
    void toast(std::string message, double seconds = 4.0);

    char* cerebras_key_buffer() { return api_key_input_; }
    char* assemblyai_key_buffer() { return aai_key_input_; }
    static constexpr size_t key_buffer_size = 256;
    bool& reveal_cerebras_key() { return show_api_key_; }
    bool& reveal_assemblyai_key() { return show_aai_key_; }
    std::string& toast_message() { return toast_message_; }
    double toast_deadline() const { return toast_until_; }

private:
    void draw_ui();

    HINSTANCE instance_ = nullptr;
    gfx::Device gfx_;
    ui::PanelWindow panel_;
    ui::RobotOverlay robot_;
    bridge::IdeBridge ide_;
    bool running_ = true;
    bool settings_dirty_ = false;

    // Transient UI state
    char api_key_input_[256]{};
    char aai_key_input_[256]{};
    bool show_api_key_ = false;
    bool show_aai_key_ = false;
    std::string toast_message_;
    double toast_until_ = 0.0;
};

// Global accessor.
App& app();

// Tab renderers (defined in ui/panel_ui.cpp and friends).
void draw_chat_tab(App& application);
void draw_transcript_tab(App& application);
void draw_tools_tab(App& application);
void draw_phone_tab(App& application);

}  // namespace argos
