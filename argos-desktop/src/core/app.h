#pragma once

// Application shell: owns the graphics device, windows and the AI services,
// and drives the frame loop.

#include <windows.h>

#include <memory>
#include <string>

#include "agent/agent.h"
#include "bridge/ide_bridge.h"
#include "gfx/device.h"
#include "ui/panel_window.h"
#include "ui/robot_overlay.h"
#include "ui/summary_overlay.h"

namespace argos {

class App {
public:
    bool init(HINSTANCE hinst);
    int run();
    void request_exit() { running_ = false; }

    gfx::Device& gfx() { return gfx_; }
    ui::PanelWindow& panel() { return panel_; }
    ui::RobotOverlay& robot() { return robot_; }
    ui::SummaryOverlay& summary() { return summary_; }
    bridge::IdeBridge& ide() { return ide_; }
    agent::Agent& agent() { return agent_; }
    HINSTANCE hinst() const { return instance_; }

    // ── UI state shared with ui/panel_ui.cpp ──
    bool keys_present() const;
    void save_settings();
    void apply_settings_from_ui();
    bool& settings_dirty() { return settings_dirty_; }
    void toast(std::string message, double seconds = 4.0);

    char* murf_key_buffer() { return murf_key_input_; }
    char* speechmatics_key_buffer() { return sm_key_input_; }
    char* backend_key_buffer() { return backend_key_input_; }
    static constexpr size_t key_buffer_size = 256;
    bool& reveal_murf_key() { return show_murf_key_; }
    bool& reveal_speechmatics_key() { return show_sm_key_; }
    bool& reveal_backend_key() { return show_backend_key_; }
    std::string& toast_message() { return toast_message_; }
    double toast_deadline() const { return toast_until_; }

private:
    void draw_ui();

    HINSTANCE instance_ = nullptr;
    gfx::Device gfx_;
    ui::PanelWindow panel_;
    ui::RobotOverlay robot_;
    ui::SummaryOverlay summary_;
    bridge::IdeBridge ide_;
    agent::Agent agent_;
    bool running_ = true;
    bool settings_dirty_ = false;

    // Transient UI state
    char murf_key_input_[256]{};
    char sm_key_input_[256]{};
    char backend_key_input_[256]{};
    bool show_murf_key_ = false;
    bool show_sm_key_ = false;
    bool show_backend_key_ = false;
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
