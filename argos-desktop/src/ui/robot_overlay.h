#pragma once

// The floating Argos robot.
//
// Hosts the SAME Three.js scene the Android app uses (assets/argos_robot.html)
// in a WebView2 control, so the desktop robot is visually identical to the
// phone one — same geometry, expressions, gestures and nanotech animation.
//
// Two deliberate design choices keep the desktop usable at all times:
//
//   1. The window is only as large as the robot (a few hundred pixels), never
//      full-screen. A full-screen transparent overlay that mis-handles hit
//      testing swallows every click on the desktop — see
//      github.com/MicrosoftEdge/WebView2Feedback/issues/5668. With a small
//      window the worst case is a small dead zone around the robot.
//
//   2. WS_EX_TRANSPARENT (click-through) is the DEFAULT. It is removed only
//      while the cursor is actually over the robot's head, and restored
//      immediately afterwards.
//
// Transparency uses WebView2 "visual hosting": the control renders into an
// IDCompositionVisual that we own, which gives real per-pixel alpha over the
// desktop instead of a colour-keyed approximation.

#include <windows.h>

#include <dcomp.h>
#include <wrl/client.h>

#include <WebView2.h>

#include <functional>
#include <string>

namespace argos::ui {

class RobotOverlay {
public:
    // Fired when the user taps the robot's head (the Android "double-tap to
    // talk" affordance).
    using TapHandler = std::function<void()>;

    bool create(HINSTANCE instance);
    void destroy();

    bool ready() const { return webview_ != nullptr; }
    HWND hwnd() const { return hwnd_; }

    void show(bool enable);
    bool visible() const { return visible_; }

    // ── Driving the robot (mirrors the Android ArgosJS interface) ──
    void set_expression(const std::string& name);
    void set_hand_gesture(const std::string& name);
    void set_state(const std::string& name);
    void set_thinking(bool on);
    void set_talking(bool on);
    void set_listening(bool on);
    void set_recording(bool on);
    void on_speak_word(const std::string& word, int index);
    void set_standby(bool on);
    void hide_eyes();
    void show_eyes();
    void move_to(int x, int y);
    void set_robot_size(int size);
    void play_expression_sequence(const std::string& json_array);

    // Runs arbitrary JS against the scene (used by the tool executor for the
    // less common ArgosJS calls).
    void eval_js(const std::string& script);

    void set_tap_handler(TapHandler handler) { on_tap_ = std::move(handler); }

    // Pumps the click-through state machine; call once per frame.
    void tick();

private:
    static LRESULT CALLBACK wnd_proc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam);
    LRESULT handle_message(UINT msg, WPARAM wparam, LPARAM lparam);

    bool create_window(HINSTANCE instance);
    bool create_composition();
    void start_webview();
    void on_webview_ready();
    void on_web_message(const std::wstring& message);

    // Moves/resizes the window to follow the robot as the scene roams.
    void apply_robot_bounds(int x, int y, int size);
    void update_click_through();
    void ensure_dispatcher_queue();

    HWND hwnd_ = nullptr;
    HINSTANCE instance_ = nullptr;

    Microsoft::WRL::ComPtr<IDCompositionDevice> dcomp_device_;
    Microsoft::WRL::ComPtr<IDCompositionTarget> dcomp_target_;
    Microsoft::WRL::ComPtr<IDCompositionVisual> dcomp_root_;

    Microsoft::WRL::ComPtr<ICoreWebView2Environment> environment_;
    Microsoft::WRL::ComPtr<ICoreWebView2CompositionController> composition_controller_;
    Microsoft::WRL::ComPtr<ICoreWebView2Controller> controller_;
    Microsoft::WRL::ComPtr<ICoreWebView2> webview_;

    // Virtual desktop geometry the scene roams across.
    int screen_origin_x_ = 0;
    int screen_origin_y_ = 0;
    int screen_width_ = 1920;
    int screen_height_ = 1080;

    // Current robot box in virtual-desktop pixels. The box is wider than it is
    // tall (2.0 : 1.7), matching the Android overlay so the aura rings and
    // hands are not clipped.
    int robot_x_ = 0;
    int robot_y_ = 0;
    int robot_w_ = 480;
    int robot_h_ = 408;
    // Logical size handed to the scene (drives its roaming margins), before the
    // desktop magnification in config().overlay.robot_zoom is applied.
    int scene_robot_size_ = 240;

    bool visible_ = true;
    bool click_through_ = true;  // start click-through: never block the desktop
    bool dragging_ = false;
    POINT drag_grab_{};
    ULONGLONG last_heartbeat_ = 0;

    TapHandler on_tap_;
};

}  // namespace argos::ui
