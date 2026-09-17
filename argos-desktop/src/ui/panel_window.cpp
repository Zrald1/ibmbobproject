#include "ui/panel_window.h"

#include <dwmapi.h>
#include <imgui.h>
#include <imgui_impl_dx11.h>
#include <imgui_impl_win32.h>

#include <filesystem>

#include "core/config.h"
#include "core/log.h"
#include "platform/win_util.h"

extern IMGUI_IMPL_API LRESULT ImGui_ImplWin32_WndProcHandler(HWND hwnd, UINT msg, WPARAM wparam,
                                                             LPARAM lparam);

namespace argos::ui {
namespace {

constexpr wchar_t kWindowClass[] = L"ArgosDesktopPanel";

bool register_window_class(HINSTANCE instance) {
    static bool registered = false;
    if (registered) return true;

    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.style = CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc = PanelWindow::wnd_proc_stub();
    wc.hInstance = instance;
    wc.hCursor = ::LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = nullptr;
    wc.lpszClassName = kWindowClass;

    if (!::RegisterClassExW(&wc)) {
        log::error("RegisterClassExW failed for the panel window");
        return false;
    }
    registered = true;
    return true;
}

// Rounded, dark title bar to match the in-app theme on Windows 11.
void apply_dark_titlebar(HWND hwnd) {
    BOOL dark = TRUE;
    ::DwmSetWindowAttribute(hwnd, 20 /*DWMWA_USE_IMMERSIVE_DARK_MODE*/, &dark, sizeof(dark));
    const COLORREF caption = RGB(10, 12, 16);
    ::DwmSetWindowAttribute(hwnd, 35 /*DWMWA_CAPTION_COLOR*/, &caption, sizeof(caption));
    const COLORREF text = RGB(0, 229, 204);
    ::DwmSetWindowAttribute(hwnd, 36 /*DWMWA_TEXT_COLOR*/, &text, sizeof(text));
}

void load_fonts() {
    ImGuiIO& io = ImGui::GetIO();
    io.Fonts->Clear();

    // Segoe UI keeps the panel consistent with the rest of Windows; if it is
    // somehow missing we silently keep the built-in font.
    const std::filesystem::path segoe = L"C:\\Windows\\Fonts\\segoeui.ttf";
    ImFontConfig cfg;
    cfg.OversampleH = 2;
    cfg.OversampleV = 2;
    cfg.PixelSnapH = false;

    if (std::filesystem::exists(segoe)) {
        if (!io.Fonts->AddFontFromFileTTF(segoe.string().c_str(), 16.0f, &cfg)) {
            io.Fonts->AddFontDefault();
        }
    } else {
        io.Fonts->AddFontDefault();
    }
}

void apply_theme() {
    ImGuiStyle& style = ImGui::GetStyle();
    style.WindowRounding = 8.0f;
    style.FrameRounding = 6.0f;
    style.GrabRounding = 6.0f;
    style.PopupRounding = 6.0f;
    style.ScrollbarRounding = 8.0f;
    style.TabRounding = 6.0f;
    style.WindowBorderSize = 0.0f;
    style.FrameBorderSize = 0.0f;
    style.WindowPadding = ImVec2(14, 14);
    style.FramePadding = ImVec2(10, 6);
    style.ItemSpacing = ImVec2(10, 8);
    style.ScrollbarSize = 12.0f;

    ImVec4* colors = style.Colors;
    const ImVec4 cyan(0.00f, 0.90f, 0.80f, 1.00f);
    const ImVec4 cyan_dim(0.00f, 0.55f, 0.50f, 1.00f);
    const ImVec4 bg(0.055f, 0.063f, 0.078f, 1.00f);
    const ImVec4 bg_light(0.10f, 0.11f, 0.13f, 1.00f);

    colors[ImGuiCol_Text] = ImVec4(0.90f, 0.92f, 0.94f, 1.00f);
    colors[ImGuiCol_TextDisabled] = ImVec4(0.45f, 0.48f, 0.52f, 1.00f);
    colors[ImGuiCol_WindowBg] = bg;
    colors[ImGuiCol_ChildBg] = ImVec4(0.07f, 0.08f, 0.10f, 1.00f);
    colors[ImGuiCol_PopupBg] = ImVec4(0.08f, 0.09f, 0.11f, 0.98f);
    colors[ImGuiCol_Border] = ImVec4(0.20f, 0.22f, 0.26f, 0.60f);
    colors[ImGuiCol_FrameBg] = bg_light;
    colors[ImGuiCol_FrameBgHovered] = ImVec4(0.15f, 0.17f, 0.20f, 1.00f);
    colors[ImGuiCol_FrameBgActive] = ImVec4(0.18f, 0.20f, 0.24f, 1.00f);
    colors[ImGuiCol_TitleBg] = bg;
    colors[ImGuiCol_TitleBgActive] = bg;
    colors[ImGuiCol_MenuBarBg] = bg;
    colors[ImGuiCol_ScrollbarBg] = ImVec4(0.06f, 0.07f, 0.09f, 1.00f);
    colors[ImGuiCol_ScrollbarGrab] = ImVec4(0.20f, 0.22f, 0.26f, 1.00f);
    colors[ImGuiCol_ScrollbarGrabHovered] = cyan_dim;
    colors[ImGuiCol_ScrollbarGrabActive] = cyan;
    colors[ImGuiCol_CheckMark] = cyan;
    colors[ImGuiCol_SliderGrab] = cyan_dim;
    colors[ImGuiCol_SliderGrabActive] = cyan;
    colors[ImGuiCol_Button] = ImVec4(0.13f, 0.15f, 0.18f, 1.00f);
    colors[ImGuiCol_ButtonHovered] = ImVec4(0.00f, 0.40f, 0.37f, 1.00f);
    colors[ImGuiCol_ButtonActive] = ImVec4(0.00f, 0.60f, 0.55f, 1.00f);
    colors[ImGuiCol_Header] = ImVec4(0.12f, 0.14f, 0.17f, 1.00f);
    colors[ImGuiCol_HeaderHovered] = ImVec4(0.00f, 0.40f, 0.37f, 1.00f);
    colors[ImGuiCol_HeaderActive] = ImVec4(0.00f, 0.60f, 0.55f, 1.00f);
    colors[ImGuiCol_Separator] = ImVec4(0.20f, 0.22f, 0.26f, 0.60f);
    colors[ImGuiCol_Tab] = ImVec4(0.09f, 0.10f, 0.12f, 1.00f);
    colors[ImGuiCol_TabHovered] = ImVec4(0.00f, 0.40f, 0.37f, 1.00f);
    colors[ImGuiCol_TabSelected] = ImVec4(0.00f, 0.28f, 0.26f, 1.00f);
    colors[ImGuiCol_PlotLines] = cyan;
    colors[ImGuiCol_TextSelectedBg] = ImVec4(0.00f, 0.60f, 0.55f, 0.45f);
    colors[ImGuiCol_NavCursor] = cyan;
}

}  // namespace

WNDPROC PanelWindow::wnd_proc_stub() { return &PanelWindow::wnd_proc; }

bool PanelWindow::create(gfx::Device& device, HINSTANCE instance, const std::wstring& title) {
    device_ = &device;
    if (!register_window_class(instance)) return false;

    RECT rect{0, 0, static_cast<LONG>(width_), static_cast<LONG>(height_)};
    ::AdjustWindowRectEx(&rect, WS_OVERLAPPEDWINDOW, FALSE, 0);

    hwnd_ = ::CreateWindowExW(0, kWindowClass, title.c_str(), WS_OVERLAPPEDWINDOW,
                              CW_USEDEFAULT, CW_USEDEFAULT, rect.right - rect.left,
                              rect.bottom - rect.top, nullptr, nullptr, instance, this);
    if (!hwnd_) {
        log::error("CreateWindowExW failed for the panel window");
        return false;
    }

    apply_dark_titlebar(hwnd_);

    if (!create_swapchain()) return false;

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;  // don't litter the working directory
    io.ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard;

    load_fonts();
    apply_theme();

    if (!ImGui_ImplWin32_Init(hwnd_) || !ImGui_ImplDX11_Init(device.d3d(), device.ctx())) {
        log::error("ImGui backends failed to initialise");
        return false;
    }
    imgui_ready_ = true;

    ::ShowWindow(hwnd_, SW_SHOW);
    ::UpdateWindow(hwnd_);
    log::info("Panel window ready");
    return true;
}

bool PanelWindow::create_swapchain() {
    DXGI_SWAP_CHAIN_DESC1 desc{};
    desc.Width = width_;
    desc.Height = height_;
    desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    desc.Stereo = FALSE;
    desc.SampleDesc.Count = 1;
    desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    desc.BufferCount = 2;
    desc.Scaling = DXGI_SCALING_STRETCH;
    desc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    desc.AlphaMode = DXGI_ALPHA_MODE_IGNORE;

    const HRESULT hr = device_->factory()->CreateSwapChainForHwnd(
        device_->d3d(), hwnd_, &desc, nullptr, nullptr, &swapchain_);
    if (FAILED(hr)) {
        log::error("CreateSwapChainForHwnd failed: " + win::hr_text(hr));
        return false;
    }
    device_->factory()->MakeWindowAssociation(hwnd_, DXGI_MWA_NO_ALT_ENTER);
    create_targets();
    return true;
}

void PanelWindow::release_targets() { rtv_.Reset(); }

void PanelWindow::create_targets() {
    Microsoft::WRL::ComPtr<ID3D11Texture2D> backbuffer;
    if (FAILED(swapchain_->GetBuffer(0, IID_PPV_ARGS(&backbuffer)))) return;
    device_->d3d()->CreateRenderTargetView(backbuffer.Get(), nullptr, &rtv_);
}

void PanelWindow::resize(UINT width, UINT height) {
    if (!swapchain_ || width == 0 || height == 0) return;
    if (width == width_ && height == height_) return;

    width_ = width;
    height_ = height;

    device_->ctx()->OMSetRenderTargets(0, nullptr, nullptr);
    release_targets();
    if (FAILED(swapchain_->ResizeBuffers(0, width, height, DXGI_FORMAT_UNKNOWN, 0))) {
        log::warn("ResizeBuffers failed");
    }
    create_targets();
}

void PanelWindow::show() {
    if (!hwnd_) return;
    ::ShowWindow(hwnd_, SW_SHOW);
    visible_ = true;
}

void PanelWindow::hide() {
    if (!hwnd_) return;
    ::ShowWindow(hwnd_, SW_HIDE);
    visible_ = false;
}

void PanelWindow::toggle() { visible_ ? hide() : show(); }

void PanelWindow::focus() {
    if (!hwnd_) return;
    show();
    ::SetForegroundWindow(hwnd_);
}

void PanelWindow::destroy() {
    if (imgui_ready_) {
        ImGui_ImplDX11_Shutdown();
        ImGui_ImplWin32_Shutdown();
        ImGui::DestroyContext();
        imgui_ready_ = false;
    }
    release_targets();
    swapchain_.Reset();
    if (hwnd_) {
        ::DestroyWindow(hwnd_);
        hwnd_ = nullptr;
    }
}

bool PanelWindow::render_frame(const UiCallback& draw_ui) {
    if (!hwnd_ || !visible_ || !rtv_) {
        ::Sleep(8);
        return true;
    }

    ImGui_ImplDX11_NewFrame();
    ImGui_ImplWin32_NewFrame();
    ImGui::NewFrame();

    if (draw_ui) {
        draw_ui();
    } else if (draw_ui_) {
        draw_ui_();
    }

    ImGui::Render();

    const float clear[4] = {0.055f, 0.063f, 0.078f, 1.0f};
    const D3D11_VIEWPORT viewport = gfx::full_viewport(static_cast<float>(width_),
                                                       static_cast<float>(height_));
    device_->ctx()->OMSetRenderTargets(1, rtv_.GetAddressOf(), nullptr);
    device_->ctx()->ClearRenderTargetView(rtv_.Get(), clear);
    device_->ctx()->RSSetViewports(1, &viewport);
    ImGui_ImplDX11_RenderDrawData(ImGui::GetDrawData());

    const HRESULT hr = swapchain_->Present(1, 0);
    if (hr == DXGI_ERROR_DEVICE_REMOVED || hr == DXGI_ERROR_DEVICE_RESET) {
        log::error("Graphics device lost — restart Argos.");
        return false;
    }
    return true;
}

LRESULT CALLBACK PanelWindow::wnd_proc(HWND hwnd, UINT msg, WPARAM wparam, LPARAM lparam) {
    PanelWindow* self = nullptr;
    if (msg == WM_NCCREATE) {
        auto* cs = reinterpret_cast<CREATESTRUCTW*>(lparam);
        self = static_cast<PanelWindow*>(cs->lpCreateParams);
        ::SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(self));
        if (self) self->hwnd_ = hwnd;
    } else {
        self = reinterpret_cast<PanelWindow*>(::GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    }

    if (self && ImGui_ImplWin32_WndProcHandler(hwnd, msg, wparam, lparam)) return TRUE;
    if (self) return self->handle_message(msg, wparam, lparam);
    return ::DefWindowProcW(hwnd, msg, wparam, lparam);
}

LRESULT PanelWindow::handle_message(UINT msg, WPARAM wparam, LPARAM lparam) {
    switch (msg) {
        case WM_SIZE:
            if (wparam != SIZE_MINIMIZED) {
                resize(LOWORD(lparam), HIWORD(lparam));
            }
            return 0;

        case WM_SYSCOMMAND:
            if ((wparam & 0xFFF0) == SC_KEYMENU) return 0;  // no Alt menu flash
            break;

        case WM_ERASEBKGND:
            return 1;

        case WM_CLOSE:
            hide();  // Argos keeps running in the tray/overlay
            return 0;

        case WM_DESTROY:
            ::PostQuitMessage(0);
            return 0;

        default:
            break;
    }
    return ::DefWindowProcW(hwnd_, msg, wparam, lparam);
}

}  // namespace argos::ui
