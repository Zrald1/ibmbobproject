#include <windows.h>

#include <format>

#include "core/app.h"
#include "core/config.h"
#include "core/log.h"
#include "platform/win_util.h"

namespace {

// If Argos ever faults, the last thing it must do is get out of the user's way:
// log where it happened and hide the always-on-top overlay so the desktop is
// usable again.
LONG WINAPI crash_handler(EXCEPTION_POINTERS* info) {
    const DWORD code = info && info->ExceptionRecord ? info->ExceptionRecord->ExceptionCode : 0;
    void* address = info && info->ExceptionRecord ? info->ExceptionRecord->ExceptionAddress : nullptr;

    wchar_t module_path[MAX_PATH] = L"(unknown)";
    HMODULE module = nullptr;
    if (::GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                                 GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                             reinterpret_cast<LPCWSTR>(address), &module) &&
        module) {
        ::GetModuleFileNameW(module, module_path, MAX_PATH);
    }

    const auto offset = reinterpret_cast<uintptr_t>(address) - reinterpret_cast<uintptr_t>(module);
    argos::log::error(std::format("CRASH: exception 0x{:08X} in {} +0x{:X}", code,
                                  argos::win::to_utf8(module_path), offset));

    if (HWND overlay = ::FindWindowW(L"ArgosDesktopOverlay", nullptr)) {
        ::ShowWindow(overlay, SW_HIDE);
    }
    if (HWND panel = ::FindWindowW(L"ArgosDesktopPanel", nullptr)) {
        ::ShowWindow(panel, SW_HIDE);
    }

    ::MessageBoxW(nullptr,
                  L"Argos hit an unexpected error and has been hidden. Details are in "
                  L"%APPDATA%\\ArgosDesktop\\logs.",
                  L"Argos Desktop", MB_ICONERROR | MB_OK);
    return EXCEPTION_EXECUTE_HANDLER;
}

}  // namespace

int APIENTRY wWinMain(HINSTANCE instance, HINSTANCE /*prev*/, LPWSTR /*cmdline*/, int /*show*/) {
    ::SetUnhandledExceptionFilter(&crash_handler);
    ::SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);

    // One Argos at a time — a second copy would fight over the overlay window
    // and open a duplicate AssemblyAI stream (which bills by connection time).
    HANDLE single = ::CreateMutexW(nullptr, TRUE, L"Local\\ArgosDesktop.SingleInstance");
    if (single && ::GetLastError() == ERROR_ALREADY_EXISTS) {
        if (HWND existing = ::FindWindowW(L"ArgosDesktopPanel", nullptr)) {
            ::SetForegroundWindow(existing);
        }
        return 0;
    }

    const HRESULT com = ::CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED |
                                                      COINIT_DISABLE_OLE1DDE);
    const bool com_ok = SUCCEEDED(com);

    argos::log::init();
    argos::log::info("── Argos Desktop starting ──");
    argos::log::info("Config: " + argos::win::to_utf8(argos::config_path().wstring()));

    argos::config_load();

    argos::App& application = argos::app();
    if (!application.init(instance)) {
        ::MessageBoxW(nullptr, L"Argos could not start. Check the log in %APPDATA%\\ArgosDesktop\\logs.",
                      L"Argos Desktop", MB_ICONERROR | MB_OK);
        if (com_ok) ::CoUninitialize();
        return 1;
    }

    const int code = application.run();

    if (com_ok) ::CoUninitialize();
    if (single) ::CloseHandle(single);
    return code;
}
