#pragma once

// Onboarding summary popup — a separate WebView2 window showing the rendered
// ONBOARDING.md (markdown + mermaid diagram) as a dark "glass" card floating
// over the desktop. Reuses the robot overlay's DirectComposition visual
// hosting so the page background can be truly transparent.
//
// Hidden by default; tools::show_onboarding_summary calls show(), the page's
// close button posts {fn:"close"} and the host hides it again.

#include <windows.h>

#include <dcomp.h>

#include <WebView2.h>
#include <WebView2EnvironmentOptions.h>

#include <wrl.h>

#include <filesystem>
#include <string>

namespace argos::ui {

class SummaryOverlay {
public:
    bool create(HINSTANCE instance);
    void destroy();

    // Pushes the payload into the page (or queues it until navigation
    // finishes) and makes the window visible. `root` is the doc's directory —
    // relative file links in the markdown resolve against it when opened in
    // the IDE.
    void show(const std::string& title, const std::string& markdown,
              const std::string& mermaid, const std::filesystem::path& root);
    void hide();
    bool visible() const { return visible_; }

private:
    static LRESULT CALLBACK wnd_proc(HWND hwnd, UINT msg, WPARAM wparam,
                                     LPARAM lparam);
    LRESULT handle_message(UINT msg, WPARAM wparam, LPARAM lparam);

    bool create_window(HINSTANCE instance);
    bool create_composition();
    void ensure_dispatcher_queue();
    void start_webview();
    void on_webview_ready();
    void on_web_message(const std::wstring& message);
    void eval_js(const std::string& js);
    void push_payload();

    HINSTANCE instance_ = nullptr;
    HWND hwnd_ = nullptr;
    bool visible_ = false;
    bool page_ready_ = false;
    std::string pending_json_;           // payload waiting for page load
    std::filesystem::path root_;         // doc dir — base for file links

    Microsoft::WRL::ComPtr<IDCompositionDevice> dcomp_device_;
    Microsoft::WRL::ComPtr<IDCompositionTarget> dcomp_target_;
    Microsoft::WRL::ComPtr<IDCompositionVisual> dcomp_root_;

    Microsoft::WRL::ComPtr<ICoreWebView2Environment> environment_;
    Microsoft::WRL::ComPtr<ICoreWebView2CompositionController> composition_controller_;
    Microsoft::WRL::ComPtr<ICoreWebView2Controller> controller_;
    Microsoft::WRL::ComPtr<ICoreWebView2> webview_;
};

}  // namespace argos::ui
