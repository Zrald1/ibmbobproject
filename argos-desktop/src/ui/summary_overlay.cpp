#include "ui/summary_overlay.h"

#include <d3d11.h>
#include <dispatcherqueue.h>
#include <dxgi1_2.h>

#include <wrl/event.h>

#include <filesystem>
#include <format>

#include <nlohmann/json.hpp>

#include "core/app.h"
#include "core/log.h"
#include "platform/win_util.h"

using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;

namespace argos::ui {
namespace {

constexpr wchar_t kWindowClass[] = L"ArgosSummaryOverlay";
constexpr wchar_t kVirtualHost[] = L"argos.assets";
constexpr wchar_t kPageUrl[] = L"https://argos.assets/argos_summary.html";
constexpr int kWidth = 680;
constexpr int kHeight = 780;

}  // namespace

// ── Window ──

bool SummaryOverlay::create(HINSTANCE instance) {
    instance_ = instance;
    if (!create_window(instance)) return false;
    if (!create_composition()) return false;
    start_webview();
    return true;
}

bool SummaryOverlay::create_window(HINSTANCE instance) {
    static bool registered = false;
    if (!registered) {
        WNDCLASSEXW wc{};
        wc.cbSize = sizeof(wc);
        wc.lpfnWndProc = &SummaryOverlay::wnd_proc;
        wc.hInstance = instance;
        wc.hCursor = ::LoadCursorW(nullptr, IDC_ARROW);
        wc.hbrBackground = nullptr;
        wc.lpszClassName = kWindowClass;
        if (!::RegisterClassExW(&wc)) {
            log::error("RegisterClassExW failed for the summary overlay");
            return false;
        }
        registered = true;
    }

    const int sx = ::GetSystemMetrics(SM_XVIRTUALSCREEN);
    const int sy = ::GetSystemMetrics(SM_YVIRTUALSCREEN);
    const int sw = ::GetSystemMetrics(SM_CXVIRTUALSCREEN);
    const int sh = ::GetSystemMetrics(SM_CYVIRTUALSCREEN);
    // Float just left of the robot's corner, vertically centred.
    const int x = sx + sw - kWidth - 60;
    const int y = sy + (sh - kHeight) / 2;

    // Interactive but never steals focus; NOREDIRECTIONBITMAP keeps the
    // alpha channel for the composited WebView.
    const DWORD ex_style = WS_EX_TOPMOST | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW |
                           WS_EX_NOREDIRECTIONBITMAP;
    hwnd_ = ::CreateWindowExW(ex_style, kWindowClass, L"Argos Onboarding",
                            WS_POPUP, x, y, kWidth, kHeight, nullptr, nullptr,
                            instance, this);
    if (!hwnd_) {
        log::error("CreateWindowExW failed for the summary overlay");
        return false;
    }
    return true;  // starts hidden — show() reveals it
}

bool SummaryOverlay::create_composition() {
    ComPtr<IDCompositionDevice> device;
    ComPtr<IDXGIDevice> dxgi_device;
    ComPtr<ID3D11Device> d3d;
    HRESULT hr = ::D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                                     D3D11_CREATE_DEVICE_BGRA_SUPPORT, nullptr, 0,
                                     D3D11_SDK_VERSION, &d3d, nullptr, nullptr);
    if (FAILED(hr)) {
        log::error("D3D11CreateDevice failed for the summary overlay: " +
                   win::hr_text(hr));
        return false;
    }
    if (FAILED(d3d->QueryInterface(IID_PPV_ARGS(&dxgi_device)))) return false;
    if (FAILED(::DCompositionCreateDevice(dxgi_device.Get(),
                                          IID_PPV_ARGS(&dcomp_device_))))
        return false;
    if (FAILED(dcomp_device_->CreateTargetForHwnd(hwnd_, TRUE, &dcomp_target_)))
        return false;
    if (FAILED(dcomp_device_->CreateVisual(&dcomp_root_))) return false;
    if (FAILED(dcomp_target_->SetRoot(dcomp_root_.Get()))) return false;
    return SUCCEEDED(dcomp_device_->Commit());
}

void SummaryOverlay::ensure_dispatcher_queue() {
    static bool created = false;
    if (created) return;
    DispatcherQueueOptions options{};
    options.dwSize = sizeof(options);
    options.threadType = DQTYPE_THREAD_CURRENT;
    options.apartmentType = DQTAT_COM_ASTA;
    ABI::Windows::System::IDispatcherQueueController* controller = nullptr;
    if (SUCCEEDED(::CreateDispatcherQueueController(options, &controller)))
        created = true;
}

void SummaryOverlay::start_webview() {
    ensure_dispatcher_queue();
    const auto user_data = win::local_app_data_dir() / L"webview2";
    std::error_code ec;
    std::filesystem::create_directories(user_data, ec);

    ::CreateCoreWebView2EnvironmentWithOptions(
        nullptr, user_data.c_str(), nullptr,
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [this](HRESULT result, ICoreWebView2Environment* environment) -> HRESULT {
                if (FAILED(result) || environment == nullptr) {
                    log::error("summary overlay: WebView2 environment failed: " +
                               win::hr_text(result));
                    return result;
                }
                environment_ = environment;
                ComPtr<ICoreWebView2Environment3> environment3;
                if (FAILED(environment_->QueryInterface(IID_PPV_ARGS(&environment3))))
                    return E_NOINTERFACE;
                return environment3->CreateCoreWebView2CompositionController(
                    hwnd_,
                    Callback<
                        ICoreWebView2CreateCoreWebView2CompositionControllerCompletedHandler>(
                        [this](HRESULT rc,
                               ICoreWebView2CompositionController* composition) -> HRESULT {
                            if (FAILED(rc) || composition == nullptr) {
                                log::error("summary overlay: composition controller failed: " +
                                           win::hr_text(rc));
                                return rc;
                            }
                            composition_controller_ = composition;
                            if (FAILED(composition->QueryInterface(IID_PPV_ARGS(&controller_))))
                                return E_NOINTERFACE;
                            if (FAILED(controller_->get_CoreWebView2(&webview_)))
                                return E_FAIL;
                            on_webview_ready();
                            return S_OK;
                        })
                        .Get());
            })
            .Get());
}

void SummaryOverlay::on_webview_ready() {
    ComPtr<ICoreWebView2Controller2> controller2;
    if (SUCCEEDED(controller_->QueryInterface(IID_PPV_ARGS(&controller2)))) {
        COREWEBVIEW2_COLOR transparent{0, 0, 0, 0};
        controller2->put_DefaultBackgroundColor(transparent);
    }
    if (composition_controller_) {
        composition_controller_->put_RootVisualTarget(dcomp_root_.Get());
        dcomp_device_->Commit();
    }
    RECT bounds{0, 0, kWidth, kHeight};
    controller_->put_Bounds(bounds);
    controller_->put_IsVisible(TRUE);

    ComPtr<ICoreWebView2Settings> settings;
    if (SUCCEEDED(webview_->get_Settings(&settings))) {
        settings->put_AreDefaultContextMenusEnabled(FALSE);
        settings->put_IsStatusBarEnabled(FALSE);
        settings->put_IsZoomControlEnabled(FALSE);
    }

    ComPtr<ICoreWebView2_3> webview3;
    if (SUCCEEDED(webview_->QueryInterface(IID_PPV_ARGS(&webview3)))) {
        auto assets = win::exe_dir() / L"assets";
        if (!std::filesystem::exists(assets / L"argos_summary.html")) {
            assets = win::exe_dir().parent_path() / L"assets";
        }
        webview3->SetVirtualHostNameToFolderMapping(
            kVirtualHost, assets.c_str(),
            COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_DENY_CORS);
    }

    webview_->add_WebMessageReceived(
        Callback<ICoreWebView2WebMessageReceivedEventHandler>(
            [this](ICoreWebView2*,
                   ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                LPWSTR raw = nullptr;
                if (SUCCEEDED(args->TryGetWebMessageAsString(&raw)) && raw) {
                    on_web_message(raw);
                    ::CoTaskMemFree(raw);
                }
                return S_OK;
            })
            .Get(),
        nullptr);

    webview_->add_NavigationCompleted(
        Callback<ICoreWebView2NavigationCompletedEventHandler>(
            [this](ICoreWebView2*,
                   ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT {
                BOOL success = FALSE;
                args->get_IsSuccess(&success);
                if (!success) {
                    log::error("summary overlay page failed to load");
                    return S_OK;
                }
                page_ready_ = true;
                push_payload();
                return S_OK;
            })
            .Get(),
        nullptr);

    webview_->Navigate(kPageUrl);
}

void SummaryOverlay::on_web_message(const std::wstring& message) {
    const auto j = nlohmann::json::parse(win::to_utf8(message), nullptr, false);
    const std::string fn = j.is_object() ? j.value("fn", "") : "";
    if (fn == "close" || (!j.is_object() &&
                          message.find(L"close") != std::wstring::npos)) {
        hide();
        return;
    }
    if (fn == "open_file") {
        std::filesystem::path fp =
            std::filesystem::path(win::to_wide(j.value("path", "")));
        if (fp.empty()) return;
        if (fp.is_relative() && !root_.empty()) fp = root_ / fp;
        if (app().ide().connected()) {
            app().ide().call("file.open",
                             {{"path", win::to_utf8(fp.lexically_normal().wstring())}});
        } else {
            log::warn("summary overlay: open_file requested with no IDE bridge");
        }
    }
}

void SummaryOverlay::eval_js(const std::string& js) {
    if (webview_) webview_->ExecuteScript(win::to_wide(js).c_str(), nullptr);
}

void SummaryOverlay::push_payload() {
    if (!page_ready_ || pending_json_.empty()) return;
    eval_js("if(window.ArgosSummary){ArgosSummary.render(" + pending_json_ + ");}");
    pending_json_.clear();
}

// ── Public API ──

void SummaryOverlay::show(const std::string& title,
                          const std::string& markdown,
                          const std::string& mermaid,
                          const std::filesystem::path& root) {
    root_ = root;
    pending_json_ = nlohmann::json{{"title", title},
                                   {"markdown", markdown},
                                   {"mermaid", mermaid}}
                        .dump();
    push_payload();
    if (!visible_ && hwnd_) {
        ::ShowWindow(hwnd_, SW_SHOWNOACTIVATE);
        visible_ = true;
        // Reading mode: park the robot until the panel is dismissed.
        app().robot().set_roam_hold(true);
        app().robot().notify_activity();
    }
}

void SummaryOverlay::hide() {
    if (hwnd_) ::ShowWindow(hwnd_, SW_HIDE);
    if (visible_) {
        visible_ = false;
        app().robot().set_roam_hold(false);
    }
}

void SummaryOverlay::destroy() {
    if (webview_) {
        webview_->Stop();
        webview_.Reset();
    }
    controller_.Reset();
    composition_controller_.Reset();
    environment_.Reset();
    if (hwnd_) {
        ::DestroyWindow(hwnd_);
        hwnd_ = nullptr;
    }
}

// ── Window proc ──

LRESULT CALLBACK SummaryOverlay::wnd_proc(HWND hwnd, UINT msg, WPARAM wparam,
                                          LPARAM lparam) {
    SummaryOverlay* self = nullptr;
    if (msg == WM_NCCREATE) {
        auto* cs = reinterpret_cast<CREATESTRUCTW*>(lparam);
        self = static_cast<SummaryOverlay*>(cs->lpCreateParams);
        ::SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
    } else {
        self = reinterpret_cast<SummaryOverlay*>(
            ::GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    }
    if (self) return self->handle_message(msg, wparam, lparam);
    return ::DefWindowProcW(hwnd, msg, wparam, lparam);
}

LRESULT SummaryOverlay::handle_message(UINT msg, WPARAM wparam, LPARAM lparam) {
    switch (msg) {
        case WM_MOUSEACTIVATE:
            return MA_NOACTIVATE;  // interact without stealing focus
        case WM_DESTROY:
            return 0;
    }
    return ::DefWindowProcW(hwnd_, msg, wparam, lparam);
}

}  // namespace argos::ui
