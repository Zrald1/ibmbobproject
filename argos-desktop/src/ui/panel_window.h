#pragma once

// Main control panel window: chat, live transcript, settings, tool log.
// Hosts an ImGui context on its own D3D11 swap chain.

#include <windows.h>

#include <functional>
#include <string>

#include "gfx/device.h"

namespace argos::ui {

class PanelWindow {
public:
    // Called once per frame between NewFrame() and Render().
    using UiCallback = std::function<void()>;

    bool create(gfx::Device& device, HINSTANCE instance, const std::wstring& title);
    void destroy();

    HWND hwnd() const { return hwnd_; }
    bool visible() const { return visible_; }
    void show();
    void hide();
    void toggle();
    void focus();

    // Renders one frame if the window is visible; returns false when the app
    // should shut down.
    bool render_frame(const UiCallback& draw_ui);

    void set_ui_callback(UiCallback callback) { draw_ui_ = std::move(callback); }

    float width() const { return static_cast<float>(width_); }
    float height() const { return static_cast<float>(height_); }

    // Window procedure entry point, exposed for class registration.
    static WNDPROC wnd_proc_stub();

private:
    static LRESULT CALLBACK wnd_proc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam);
    LRESULT handle_message(UINT msg, WPARAM wparam, LPARAM lparam);

    bool create_swapchain();
    void release_targets();
    void create_targets();
    void resize(UINT width, UINT height);

    HWND hwnd_ = nullptr;
    gfx::Device* device_ = nullptr;
    Microsoft::WRL::ComPtr<IDXGISwapChain1> swapchain_;
    Microsoft::WRL::ComPtr<ID3D11RenderTargetView> rtv_;
    UiCallback draw_ui_;

    UINT width_ = 1180;
    UINT height_ = 760;
    bool visible_ = true;
    bool imgui_ready_ = false;
    bool occluded_ = false;
};

}  // namespace argos::ui
