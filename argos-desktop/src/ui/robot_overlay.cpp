#include "ui/robot_overlay.h"

#include <d3d11.h>
#include <dispatcherqueue.h>
#include <dxgi1_2.h>
#include <windowsx.h>

#include <wrl/event.h>

#include <algorithm>
#include <cmath>
#include <filesystem>
#include <format>

#include <nlohmann/json.hpp>

#include "core/config.h"
#include "core/log.h"
#include "platform/win_util.h"

using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;

namespace argos::ui {
namespace {

constexpr wchar_t kWindowClass[] = L"ArgosRobotOverlay";
constexpr UINT_PTR kTickTimer = 1;

// Serving the scene from a virtual host name rather than file:// keeps the page
// on a normal https origin, so WebGL and ES modules behave exactly as they do
// in the Android WebView.
constexpr wchar_t kVirtualHost[] = L"argos.assets";
constexpr wchar_t kSceneUrl[] = L"https://argos.assets/argos_robot.html";

std::string escape_js_string(const std::string& text) {
    // nlohmann produces a correctly quoted/escaped JS string literal.
    return nlohmann::json(text).dump();
}

}  // namespace

// ── Window ──

bool RobotOverlay::create(HINSTANCE instance) {
    instance_ = instance;

    screen_origin_x_ = ::GetSystemMetrics(SM_XVIRTUALSCREEN);
    screen_origin_y_ = ::GetSystemMetrics(SM_YVIRTUALSCREEN);
    screen_width_ = ::GetSystemMetrics(SM_CXVIRTUALSCREEN);
    screen_height_ = ::GetSystemMetrics(SM_CYVIRTUALSCREEN);

    scene_robot_size_ = std::clamp(config().overlay.robot_size, 120, 640);
    const double zoom = std::clamp(config().overlay.robot_zoom, 0.5, 6.0);
    robot_w_ = std::clamp(static_cast<int>(std::lround(scene_robot_size_ * 2.0 * zoom)), 160, 1800);
    robot_h_ = std::clamp(static_cast<int>(std::lround(scene_robot_size_ * 1.7 * zoom)), 140, 1600);
    robot_x_ = screen_origin_x_ + screen_width_ - robot_w_ - 120;
    robot_y_ = screen_origin_y_ + screen_height_ - robot_h_ - 160;
    log::info(std::format("Robot box: {}x{} px (scene size {}, zoom {:.1f}x)", robot_w_, robot_h_,
                          scene_robot_size_, zoom));

    if (!create_window(instance)) return false;
    if (!create_composition()) return false;

    start_webview();

    ::SetTimer(hwnd_, kTickTimer, 50, nullptr);
    return true;
}

bool RobotOverlay::create_window(HINSTANCE instance) {
    static bool registered = false;
    if (!registered) {
        WNDCLASSEXW wc{};
        wc.cbSize = sizeof(wc);
        wc.lpfnWndProc = &RobotOverlay::wnd_proc;
        wc.hInstance = instance;
        wc.hCursor = ::LoadCursorW(nullptr, IDC_HAND);
        wc.hbrBackground = nullptr;
        wc.lpszClassName = kWindowClass;
        if (!::RegisterClassExW(&wc)) {
            log::error("RegisterClassExW failed for the robot overlay");
            return false;
        }
        registered = true;
    }

    // WS_EX_TRANSPARENT is intentionally part of the initial style: the overlay
    // is click-through until we positively detect the cursor over the robot.
    // WS_EX_NOREDIRECTIONBITMAP is required for a DirectComposition-only
    // window (no GDI redirection surface, so the alpha channel survives).
    const DWORD ex_style = WS_EX_TOPMOST | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW |
                           WS_EX_NOREDIRECTIONBITMAP | WS_EX_TRANSPARENT;

    hwnd_ = ::CreateWindowExW(ex_style, kWindowClass, L"Argos", WS_POPUP, robot_x_, robot_y_,
                              robot_w_, robot_h_, nullptr, nullptr, instance, this);
    if (!hwnd_) {
        log::error("CreateWindowExW failed for the robot overlay");
        return false;
    }

    ::ShowWindow(hwnd_, SW_SHOWNOACTIVATE);
    return true;
}

bool RobotOverlay::create_composition() {
    // A dedicated D3D device for the overlay keeps WebView2's rendering
    // independent of the control panel's swap chain.
    ComPtr<IDCompositionDevice> device;
    ComPtr<IDXGIDevice> dxgi_device;

    ComPtr<ID3D11Device> d3d;
    const UINT flags = D3D11_CREATE_DEVICE_BGRA_SUPPORT;
    HRESULT hr = ::D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags, nullptr, 0,
                                     D3D11_SDK_VERSION, &d3d, nullptr, nullptr);
    if (FAILED(hr)) {
        log::error("D3D11CreateDevice failed for the overlay: " + win::hr_text(hr));
        return false;
    }
    if (FAILED(d3d->QueryInterface(IID_PPV_ARGS(&dxgi_device)))) return false;

    hr = ::DCompositionCreateDevice(dxgi_device.Get(), IID_PPV_ARGS(&dcomp_device_));
    if (FAILED(hr)) {
        log::error("DCompositionCreateDevice failed: " + win::hr_text(hr));
        return false;
    }
    if (FAILED(dcomp_device_->CreateTargetForHwnd(hwnd_, TRUE, &dcomp_target_))) return false;
    if (FAILED(dcomp_device_->CreateVisual(&dcomp_root_))) return false;
    if (FAILED(dcomp_target_->SetRoot(dcomp_root_.Get()))) return false;
    return SUCCEEDED(dcomp_device_->Commit());
}

void RobotOverlay::ensure_dispatcher_queue() {
    // Visual hosting requires a DispatcherQueue on the UI thread.
    static bool created = false;
    if (created) return;

    DispatcherQueueOptions options{};
    options.dwSize = sizeof(options);
    options.threadType = DQTYPE_THREAD_CURRENT;
    options.apartmentType = DQTAT_COM_ASTA;

    ABI::Windows::System::IDispatcherQueueController* controller = nullptr;
    if (SUCCEEDED(::CreateDispatcherQueueController(options, &controller))) {
        created = true;
    }
}

void RobotOverlay::start_webview() {
    ensure_dispatcher_queue();

    // Keep the browser profile out of the repo, next to the rest of our data.
    const auto user_data = win::local_app_data_dir() / L"webview2";
    std::error_code ec;
    std::filesystem::create_directories(user_data, ec);

    const HRESULT hr = ::CreateCoreWebView2EnvironmentWithOptions(
        nullptr, user_data.c_str(), nullptr,
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [this](HRESULT result, ICoreWebView2Environment* environment) -> HRESULT {
                if (FAILED(result) || environment == nullptr) {
                    log::error("WebView2 environment creation failed: " + win::hr_text(result) +
                               " — install the Microsoft Edge WebView2 Runtime.");
                    return result;
                }
                environment_ = environment;

                ComPtr<ICoreWebView2Environment3> environment3;
                if (FAILED(environment_->QueryInterface(IID_PPV_ARGS(&environment3)))) {
                    log::error("WebView2 runtime is too old for visual hosting (need 1.0.774.44+)");
                    return E_NOINTERFACE;
                }

                return environment3->CreateCoreWebView2CompositionController(
                    hwnd_,
                    Callback<ICoreWebView2CreateCoreWebView2CompositionControllerCompletedHandler>(
                        [this](HRESULT controller_result,
                               ICoreWebView2CompositionController* composition) -> HRESULT {
                            if (FAILED(controller_result) || composition == nullptr) {
                                log::error("WebView2 composition controller failed: " +
                                           win::hr_text(controller_result));
                                return controller_result;
                            }
                            composition_controller_ = composition;
                            if (FAILED(composition->QueryInterface(IID_PPV_ARGS(&controller_)))) {
                                return E_NOINTERFACE;
                            }
                            if (FAILED(controller_->get_CoreWebView2(&webview_))) {
                                return E_FAIL;
                            }
                            on_webview_ready();
                            return S_OK;
                        })
                        .Get());
            })
            .Get());

    if (FAILED(hr)) {
        log::error("CreateCoreWebView2EnvironmentWithOptions failed: " + win::hr_text(hr));
    }
}

void RobotOverlay::on_webview_ready() {
    // ── Transparent background so only the robot is drawn ──
    ComPtr<ICoreWebView2Controller2> controller2;
    if (SUCCEEDED(controller_->QueryInterface(IID_PPV_ARGS(&controller2)))) {
        COREWEBVIEW2_COLOR transparent{0, 0, 0, 0};  // A = 0 → fully transparent
        controller2->put_DefaultBackgroundColor(transparent);
    }

    // ── Connect WebView2's visual tree to ours ──
    if (composition_controller_) {
        composition_controller_->put_RootVisualTarget(dcomp_root_.Get());
        dcomp_device_->Commit();
    }

    RECT bounds{0, 0, robot_w_, robot_h_};
    controller_->put_Bounds(bounds);
    controller_->put_IsVisible(TRUE);

    // ── Trim the browser chrome we don't want in an overlay ──
    ComPtr<ICoreWebView2Settings> settings;
    if (SUCCEEDED(webview_->get_Settings(&settings))) {
        settings->put_AreDefaultContextMenusEnabled(FALSE);
        settings->put_IsStatusBarEnabled(FALSE);
        settings->put_AreDevToolsEnabled(TRUE);  // useful while iterating
        settings->put_IsZoomControlEnabled(FALSE);
    }

    // ── Serve assets/ as a virtual host ──
    ComPtr<ICoreWebView2_3> webview3;
    if (SUCCEEDED(webview_->QueryInterface(IID_PPV_ARGS(&webview3)))) {
        auto assets = win::exe_dir() / L"assets";
        if (!std::filesystem::exists(assets / L"argos_robot.html")) {
            assets = win::exe_dir().parent_path() / L"assets";
        }
        const HRESULT mapped = webview3->SetVirtualHostNameToFolderMapping(
            kVirtualHost, assets.c_str(), COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_DENY_CORS);
        if (FAILED(mapped)) {
            log::error("Could not map the assets folder for the robot scene");
        } else {
            log::info("Robot scene assets: " + win::to_utf8(assets.wstring()));
        }
    }

    // ── JS → host bridge ──
    // The scene calls JSBridge.* on Android; on the desktop we shim those onto
    // postMessage so the same argos_robot.html runs unmodified.
    static constexpr wchar_t kBridgeShim[] = LR"(
        (function() {
            function send(name, args) {
                try {
                    window.chrome.webview.postMessage(JSON.stringify({ fn: name, args: args }));
                } catch (e) {}
            }
            window.JSBridge = {
                onRobotPosition: function(x, y, size) { send('onRobotPosition', [x, y, size]); },
                onHeadTap:       function()          { send('onHeadTap', []); },
                onBodyTap:       function()          { send('onBodyTap', []); },
                onLongPress:     function(x, y)      { send('onLongPress', [x, y]); },
                onTouchDown:     function(x, y)      { send('onTouchDown', [x, y]); },
                onTouchMove:     function(x, y, d)   { send('onTouchMove', [x, y, d]); },
                onDragEnd:       function()          { send('onDragEnd', []); },
                onDiag:          function(msg)       { send('onDiag', [msg]); },
                onHandTrackingStatus: function(s)    { send('onHandTrackingStatus', [s]); },
                onHandGrab:      function(x, y)      { send('onHandGrab', [x, y]); },
                onHandDrag:      function(x, y)      { send('onHandDrag', [x, y]); },
                onHandRelease:   function(x, y)      { send('onHandRelease', [x, y]); }
            };
            // The overlay window is exactly robot-sized, so the scene must draw
            // the robot centred and let the host move the window instead.
            document.documentElement.style.background = 'transparent';
            document.body && (document.body.style.background = 'transparent');
        })();
    )";
    webview_->AddScriptToExecuteOnDocumentCreated(kBridgeShim, nullptr);

    webview_->add_WebMessageReceived(
        Callback<ICoreWebView2WebMessageReceivedEventHandler>(
            [this](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
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
            [this](ICoreWebView2*, ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT {
                BOOL success = FALSE;
                args->get_IsSuccess(&success);
                if (!success) {
                    COREWEBVIEW2_WEB_ERROR_STATUS status{};
                    args->get_WebErrorStatus(&status);
                    log::error("Robot scene failed to load (status " +
                               std::to_string(static_cast<int>(status)) + ")");
                    return S_OK;
                }

                // Hand the scene the same startup values Android sends in
                // onPageFinished().
                eval_js(std::format("ArgosJS.setScreenSize({},{});", screen_width_, screen_height_));
                eval_js(std::format("ArgosJS.setPosition({},{});",
                                    robot_x_ - screen_origin_x_ + robot_w_ / 2,
                                    robot_y_ - screen_origin_y_ + robot_h_ / 2));
                eval_js(std::format("ArgosJS.setRobotSize({});", scene_robot_size_));
                log::info("Robot scene loaded (Three.js, identical to the Android build)");
                return S_OK;
            })
            .Get(),
        nullptr);

    webview_->Navigate(kSceneUrl);
}

// ── JS → host ──

void RobotOverlay::on_web_message(const std::wstring& message) {
    nlohmann::json parsed;
    try {
        parsed = nlohmann::json::parse(win::to_utf8(message));
    } catch (const nlohmann::json::exception&) {
        return;
    }

    const std::string fn = parsed.value("fn", "");
    const auto& args = parsed["args"];

    if (fn == "onRobotPosition" && args.is_array() && args.size() >= 3) {
        apply_robot_bounds(static_cast<int>(std::lround(args[0].get<double>())),
                           static_cast<int>(std::lround(args[1].get<double>())),
                           static_cast<int>(std::lround(args[2].get<double>())));
        return;
    }

    if (fn == "onHeadTap" || fn == "onBodyTap") {
        if (on_tap_) on_tap_();
        return;
    }

    if (fn == "onDiag") {
        last_heartbeat_ = ::GetTickCount64();
        return;
    }

    if (fn == "onDragEnd") {
        dragging_ = false;
        return;
    }
}

void RobotOverlay::apply_robot_bounds(int x, int y, int size) {
    // Window padding copied from the Android service: the box is 2.0x the
    // robot's effective size wide and 1.7x tall, which is what gives the aura
    // rings, neon hands and fresnel rim room instead of clipping them at the
    // canvas edge (FloatingRobotService: winW = size * 2.0f, winH = size * 1.7f).
    //
    // robot_zoom is an extra desktop magnification on top of that, because the
    // scene sizes itself for a phone screen.
    const double zoom = std::clamp(config().overlay.robot_zoom, 0.5, 6.0);
    const int window_w = std::clamp(static_cast<int>(std::lround(size * 2.0 * zoom)), 160, 1800);
    const int window_h = std::clamp(static_cast<int>(std::lround(size * 1.7 * zoom)), 140, 1600);

    // Centre the box on the robot's anchor, keeping it fully on screen.
    const int left = std::clamp(screen_origin_x_ + x - window_w / 2, screen_origin_x_,
                                screen_origin_x_ + screen_width_ - window_w);
    const int top = std::clamp(screen_origin_y_ + y - window_h / 2, screen_origin_y_,
                               screen_origin_y_ + screen_height_ - window_h);

    // The scene animates its own scale continuously; resizing on every single
    // pixel would thrash SetWindowPos and flicker. Only resize on a real change.
    const bool moved = (left != robot_x_ || top != robot_y_);
    const bool resized = std::abs(window_w - robot_w_) > 6 || std::abs(window_h - robot_h_) > 6;
    if (!moved && !resized) return;

    robot_x_ = left;
    robot_y_ = top;
    if (resized) {
        robot_w_ = window_w;
        robot_h_ = window_h;
    }

    ::SetWindowPos(hwnd_, HWND_TOPMOST, robot_x_, robot_y_, robot_w_, robot_h_, SWP_NOACTIVATE);

    if (resized && controller_) {
        RECT bounds{0, 0, robot_w_, robot_h_};
        controller_->put_Bounds(bounds);
    }
}

// ── Host → JS ──

void RobotOverlay::eval_js(const std::string& script) {
    if (!webview_) return;
    webview_->ExecuteScript(win::to_wide(script).c_str(), nullptr);
}

void RobotOverlay::set_expression(const std::string& name) {
    eval_js("if(window.ArgosJS){ArgosJS.setExpression(" + escape_js_string(name) + ");}");
}

void RobotOverlay::set_hand_gesture(const std::string& name) {
    eval_js("if(window.ArgosJS){ArgosJS.setHandGesture(" + escape_js_string(name) + ");}");
}

void RobotOverlay::set_state(const std::string& name) {
    eval_js("if(window.ArgosJS){ArgosJS.setState(" + escape_js_string(name) + ");}");
}

void RobotOverlay::set_thinking(bool on) {
    eval_js(std::format("if(window.ArgosJS){{ArgosJS.setThinking({});}}", on ? "true" : "false"));
}

void RobotOverlay::set_talking(bool on) {
    eval_js(std::format("if(window.ArgosJS){{ArgosJS.setTalking({});}}", on ? "true" : "false"));
}

void RobotOverlay::set_listening(bool on) {
    set_state(on ? "listening" : "idle");
}

void RobotOverlay::set_recording(bool on) {
    set_state(on ? "recording" : "idle");
}

void RobotOverlay::on_speak_word(const std::string& word, int index) {
    eval_js(std::format("if(window.ArgosJS&&ArgosJS.onSpeakWord){{ArgosJS.onSpeakWord({},{});}}",
                        escape_js_string(word), index));
}

void RobotOverlay::set_standby(bool on) {
    eval_js(std::format("if(window.ArgosJS){{ArgosJS.setStandby({});}}", on ? "true" : "false"));
}

void RobotOverlay::hide_eyes() { eval_js("if(window.ArgosJS){ArgosJS.hideEyes();}"); }
void RobotOverlay::show_eyes() { eval_js("if(window.ArgosJS){ArgosJS.showEyes();}"); }

void RobotOverlay::move_to(int x, int y) {
    eval_js(std::format("if(window.ArgosJS){{ArgosJS.moveTo({},{});}}", x, y));
}

void RobotOverlay::set_robot_size(int size) {
    eval_js(std::format("if(window.ArgosJS){{ArgosJS.setRobotSize({});}}", size));
}

void RobotOverlay::play_expression_sequence(const std::string& json_array) {
    eval_js("if(window.ArgosJS&&ArgosJS.playExpressionSequence){ArgosJS.playExpressionSequence(" +
            json_array + ");}");
}

// ── Visibility and hit testing ──

void RobotOverlay::show(bool enable) {
    if (!hwnd_) return;
    visible_ = enable;
    ::ShowWindow(hwnd_, enable ? SW_SHOWNOACTIVATE : SW_HIDE);
    if (controller_) controller_->put_IsVisible(enable ? TRUE : FALSE);
}

void RobotOverlay::update_click_through() {
    if (!hwnd_ || !visible_) return;

    POINT cursor{};
    ::GetCursorPos(&cursor);

    // Only the robot's head should be grabbable. The box is padded 2.0x/1.7x
    // for the aura and hands, so the head covers roughly the middle quarter —
    // everything outside that stays click-through.
    const float cx = static_cast<float>(robot_x_) + static_cast<float>(robot_w_) * 0.5f;
    const float cy = static_cast<float>(robot_y_) + static_cast<float>(robot_h_) * 0.5f;
    const float dx = static_cast<float>(cursor.x) - cx;
    const float dy = static_cast<float>(cursor.y) - cy;
    const float radius = static_cast<float>(std::min(robot_w_, robot_h_)) * 0.26f;

    const bool over_robot = (dx * dx + dy * dy) <= (radius * radius);
    const bool want_interactive = over_robot || dragging_;

    const LONG_PTR style = ::GetWindowLongPtrW(hwnd_, GWL_EXSTYLE);
    const bool is_click_through = (style & WS_EX_TRANSPARENT) != 0;

    if (want_interactive && is_click_through) {
        ::SetWindowLongPtrW(hwnd_, GWL_EXSTYLE, style & ~static_cast<LONG_PTR>(WS_EX_TRANSPARENT));
        click_through_ = false;
    } else if (!want_interactive && !is_click_through) {
        ::SetWindowLongPtrW(hwnd_, GWL_EXSTYLE, style | WS_EX_TRANSPARENT);
        click_through_ = true;
    }
}

void RobotOverlay::tick() { update_click_through(); }

void RobotOverlay::destroy() {
    if (hwnd_) ::KillTimer(hwnd_, kTickTimer);

    if (controller_) {
        controller_->put_IsVisible(FALSE);
        controller_->Close();
    }
    webview_.Reset();
    controller_.Reset();
    composition_controller_.Reset();
    environment_.Reset();

    dcomp_root_.Reset();
    dcomp_target_.Reset();
    dcomp_device_.Reset();

    if (hwnd_) {
        ::DestroyWindow(hwnd_);
        hwnd_ = nullptr;
    }
}

// ── Window procedure ──

LRESULT CALLBACK RobotOverlay::wnd_proc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam) {
    RobotOverlay* self = nullptr;
    if (msg == WM_NCCREATE) {
        auto* cs = reinterpret_cast<CREATESTRUCTW*>(lparam);
        self = static_cast<RobotOverlay*>(cs->lpCreateParams);
        ::SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
        if (self) self->hwnd_ = hwnd;
    } else {
        self = reinterpret_cast<RobotOverlay*>(::GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    }

    if (self) return self->handle_message(msg, wparam, lparam);
    return ::DefWindowProcW(hwnd, msg, wparam, lparam);
}

LRESULT RobotOverlay::handle_message(UINT msg, WPARAM wparam, LPARAM lparam) {
    switch (msg) {
        case WM_TIMER:
            if (wparam == kTickTimer) {
                update_click_through();
                return 0;
            }
            break;

        case WM_MOUSEACTIVATE:
            return MA_NOACTIVATE;  // never take focus from the user's work

        case WM_LBUTTONDOWN: {
            dragging_ = true;
            POINT cursor{};
            ::GetCursorPos(&cursor);
            drag_grab_ = {cursor.x - robot_x_, cursor.y - robot_y_};
            ::SetCapture(hwnd_);
            eval_js("if(window.ArgosJS){ArgosJS.setDragging(true);}");
            return 0;
        }

        case WM_MOUSEMOVE:
            if (dragging_) {
                POINT cursor{};
                ::GetCursorPos(&cursor);
                const int left = cursor.x - drag_grab_.x;
                const int top = cursor.y - drag_grab_.y;
                // Tell the scene where it now lives so its roaming logic stays
                // in sync, then follow with the window.
                eval_js(std::format("if(window.ArgosJS){{ArgosJS.setPosition({},{});}}",
                                    left - screen_origin_x_ + robot_w_ / 2,
                                    top - screen_origin_y_ + robot_h_ / 2));
                robot_x_ = left;
                robot_y_ = top;
                ::SetWindowPos(hwnd_, HWND_TOPMOST, robot_x_, robot_y_, robot_w_, robot_h_,
                               SWP_NOACTIVATE);
            }
            return 0;

        case WM_LBUTTONUP:
            if (dragging_) {
                dragging_ = false;
                ::ReleaseCapture();
                eval_js("if(window.ArgosJS){ArgosJS.setDragging(false);}");
            }
            return 0;

        case WM_LBUTTONDBLCLK:
            if (on_tap_) on_tap_();
            return 0;

        case WM_DISPLAYCHANGE:
            screen_origin_x_ = ::GetSystemMetrics(SM_XVIRTUALSCREEN);
            screen_origin_y_ = ::GetSystemMetrics(SM_YVIRTUALSCREEN);
            screen_width_ = ::GetSystemMetrics(SM_CXVIRTUALSCREEN);
            screen_height_ = ::GetSystemMetrics(SM_CYVIRTUALSCREEN);
            eval_js(std::format("if(window.ArgosJS){{ArgosJS.setScreenSize({},{});}}", screen_width_,
                                screen_height_));
            return 0;

        case WM_ERASEBKGND:
            return 1;

        default:
            break;
    }
    return ::DefWindowProcW(hwnd_, msg, wparam, lparam);
}

}  // namespace argos::ui
