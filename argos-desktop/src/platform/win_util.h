#pragma once

// Small Win32 helpers shared across the app: UTF-8 <-> UTF-16 conversion,
// filesystem locations, clipboard, DPAPI secret wrapping and base64.
//
// Everything here is deliberately dependency-free (Windows SDK only) so the
// rest of the app never has to think about encodings or COM setup.

#include <windows.h>

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace argos::win {

// ── Strings ──
std::string to_utf8(std::wstring_view wide);
std::wstring to_wide(std::string_view utf8);
std::string hr_text(HRESULT hr);

// ── Filesystem locations ──
// %APPDATA%\ArgosDesktop — config, logs. Created on demand.
std::filesystem::path app_data_dir();
// %LOCALAPPDATA%\ArgosDesktop — caches (screenshots, temp audio).
std::filesystem::path local_app_data_dir();
// %USERPROFILE%\Documents
std::filesystem::path documents_dir();
// %USERPROFILE%\Documents\Argos — notes, transcripts, screenshots.
std::filesystem::path argos_documents_dir();
// Folder containing argos.exe
std::filesystem::path exe_dir();

std::string get_env_utf8(const char* name);

// ── Clipboard ──
bool set_clipboard_text(std::wstring_view text);
std::optional<std::wstring> get_clipboard_text();

// ── Input injection ──
// Brings the main window of a process (e.g. the IDE from ide-bridge.json) to
// the foreground. Required before synthetic input: SendInput only reaches the
// focused app, and the extension can only focus UI *inside* its own window.
bool bring_process_to_front(DWORD pid);
// Sends Ctrl+V then Enter through SendInput — drops a staged clipboard prompt
// into an IDE chat input that offers no programmatic submit API.
bool send_paste_enter();
// Ctrl+V only — stages the prompt in the input without submitting.
bool send_paste_only();

// ── Windows ──
std::wstring window_text(HWND hwnd);
std::wstring window_class_name(HWND hwnd);
std::wstring window_process_name(HWND hwnd);
// Title of the foreground window, or empty.
std::wstring foreground_window_title();

// ── Crypto / encoding ──
// Encrypts with the current user's credentials (DPAPI). Returns base64.
// Used so API keys are unreadable even if config.json is copied elsewhere.
std::string dpapi_protect(std::string_view plaintext);
std::optional<std::string> dpapi_unprotect(std::string_view base64_blob);

std::string base64_encode(const std::vector<unsigned char>& data);
std::string base64_encode(std::string_view data);
std::vector<unsigned char> base64_decode(std::string_view text);

// SHA-256 hex digest (used for file-version bookkeeping).
std::string sha256_hex(std::string_view data);

// Encodes BGRA8 pixel data to PNG or JPEG on disk (Windows Imaging Component).
// Returns false if the file could not be written.
bool save_png(const std::filesystem::path& path, int width, int height, const void* bgra_pixels,
              int stride_bytes = 0);
bool save_jpeg(const std::filesystem::path& path, int width, int height, const void* bgra_pixels,
               int quality = 85, int stride_bytes = 0);

}  // namespace argos::win
