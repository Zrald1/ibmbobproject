#include "platform/win_util.h"

#include <bcrypt.h>
#include <dpapi.h>
#include <shlobj.h>
#include <tlhelp32.h>
#include <wincodec.h>
#include <wrl/client.h>

#include <array>
#include <cstdio>

#pragma comment(lib, "bcrypt.lib")

namespace argos::win {

// ── Strings ──

std::string to_utf8(std::wstring_view wide) {
    if (wide.empty()) return {};
    const int needed = ::WideCharToMultiByte(CP_UTF8, 0, wide.data(), static_cast<int>(wide.size()),
                                             nullptr, 0, nullptr, nullptr);
    if (needed <= 0) return {};
    std::string out(static_cast<size_t>(needed), '\0');
    ::WideCharToMultiByte(CP_UTF8, 0, wide.data(), static_cast<int>(wide.size()), out.data(), needed,
                          nullptr, nullptr);
    return out;
}

std::wstring to_wide(std::string_view utf8) {
    if (utf8.empty()) return {};
    const int needed = ::MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()),
                                             nullptr, 0);
    if (needed <= 0) return {};
    std::wstring out(static_cast<size_t>(needed), L'\0');
    ::MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()), out.data(), needed);
    return out;
}

std::string hr_text(HRESULT hr) {
    char buf[64];
    std::snprintf(buf, sizeof(buf), "0x%08lX", static_cast<unsigned long>(hr));
    return buf;
}

// ── Filesystem locations ──

static std::filesystem::path known_folder(REFKNOWNFOLDERID id) {
    PWSTR raw = nullptr;
    if (FAILED(::SHGetKnownFolderPath(id, KF_FLAG_CREATE, nullptr, &raw)) || raw == nullptr) {
        return {};
    }
    std::filesystem::path p(raw);
    ::CoTaskMemFree(raw);
    return p;
}

static std::filesystem::path ensure(const std::filesystem::path& p) {
    if (!p.empty()) {
        std::error_code ec;
        std::filesystem::create_directories(p, ec);
    }
    return p;
}

std::filesystem::path app_data_dir() {
    static const std::filesystem::path p = [] {
        auto base = known_folder(FOLDERID_RoamingAppData);
        if (base.empty()) base = std::filesystem::temp_directory_path();
        return ensure(base / L"ArgosDesktop");
    }();
    return p;
}

std::filesystem::path local_app_data_dir() {
    static const std::filesystem::path p = [] {
        auto base = known_folder(FOLDERID_LocalAppData);
        if (base.empty()) base = std::filesystem::temp_directory_path();
        return ensure(base / L"ArgosDesktop");
    }();
    return p;
}

std::filesystem::path documents_dir() {
    static const std::filesystem::path p = [] {
        auto base = known_folder(FOLDERID_Documents);
        if (base.empty()) base = std::filesystem::current_path();
        return base;
    }();
    return p;
}

std::filesystem::path argos_documents_dir() {
    static const std::filesystem::path p = [] {
        return ensure(documents_dir() / L"Argos");
    }();
    return p;
}

std::filesystem::path exe_dir() {
    static const std::filesystem::path p = [] {
        wchar_t buf[MAX_PATH * 2]{};
        const DWORD n = ::GetModuleFileNameW(nullptr, buf, static_cast<DWORD>(std::size(buf)));
        if (n == 0) return std::filesystem::current_path();
        return std::filesystem::path(buf).parent_path();
    }();
    return p;
}

std::string get_env_utf8(const char* name) {
    const std::wstring wname = to_wide(name);
    wchar_t buf[4096]{};
    const DWORD n = ::GetEnvironmentVariableW(wname.c_str(), buf, static_cast<DWORD>(std::size(buf)));
    if (n == 0 || n >= std::size(buf)) return {};
    return to_utf8(std::wstring_view(buf, n));
}

// ── Clipboard ──

bool set_clipboard_text(std::wstring_view text) {
    if (!::OpenClipboard(nullptr)) return false;
    ::EmptyClipboard();
    const size_t bytes = (text.size() + 1) * sizeof(wchar_t);
    HGLOBAL mem = ::GlobalAlloc(GMEM_MOVEABLE, bytes);
    if (!mem) {
        ::CloseClipboard();
        return false;
    }
    if (void* dst = ::GlobalLock(mem)) {
        std::memcpy(dst, text.data(), text.size() * sizeof(wchar_t));
        static_cast<wchar_t*>(dst)[text.size()] = L'\0';
        ::GlobalUnlock(mem);
    }
    const bool ok = ::SetClipboardData(CF_UNICODETEXT, mem) != nullptr;
    if (!ok) ::GlobalFree(mem);
    ::CloseClipboard();
    return ok;
}

std::optional<std::wstring> get_clipboard_text() {
    if (!::OpenClipboard(nullptr)) return std::nullopt;
    std::optional<std::wstring> result;
    if (HANDLE data = ::GetClipboardData(CF_UNICODETEXT)) {
        if (const auto* src = static_cast<const wchar_t*>(::GlobalLock(data))) {
            result = std::wstring(src);
            ::GlobalUnlock(data);
        }
    }
    ::CloseClipboard();
    return result;
}

// ── Input injection ──

namespace {

void send_key(WORD vk, bool down) {
    INPUT in{};
    in.type = INPUT_KEYBOARD;
    in.ki.wVk = vk;
    in.ki.dwFlags = down ? 0 : KEYEVENTF_KEYUP;
    ::SendInput(1, &in, sizeof(INPUT));
}

void send_chord(WORD vk) {
    send_key(VK_CONTROL, true);
    send_key(vk, true);
    send_key(vk, false);
    send_key(VK_CONTROL, false);
}

}  // namespace

bool bring_process_to_front(DWORD pid) {
    HWND target = nullptr;
    struct Ctx {
        DWORD pid;
        HWND found;
    } ctx{pid, nullptr};
    ::EnumWindows(
        [](HWND hwnd, LPARAM lp) -> BOOL {
            auto* c = reinterpret_cast<Ctx*>(lp);
            DWORD wpid = 0;
            ::GetWindowThreadProcessId(hwnd, &wpid);
            if (wpid == c->pid && ::IsWindowVisible(hwnd) &&
                ::GetWindowTextLengthW(hwnd) > 0 && ::GetWindow(hwnd, GW_OWNER) == nullptr) {
                c->found = hwnd;
                return FALSE;
            }
            return TRUE;
        },
        reinterpret_cast<LPARAM>(&ctx));
    target = ctx.found;
    if (!target) return false;
    if (::IsIconic(target)) ::ShowWindow(target, SW_RESTORE);

    // Foreground-lock workaround: temporarily zero the timeout so any process
    // may take the foreground, then join input queues and switch.
    DWORD old_timeout = 0;
    ::SystemParametersInfoW(SPI_GETFOREGROUNDLOCKTIMEOUT, 0, &old_timeout, 0);
    ::SystemParametersInfoW(SPI_SETFOREGROUNDLOCKTIMEOUT, 0, nullptr,
                            SPIF_UPDATEINIFILE | SPIF_SENDCHANGE);
    send_key(VK_MENU, true);
    send_key(VK_MENU, false);
    const DWORD fg_thread = ::GetWindowThreadProcessId(::GetForegroundWindow(), nullptr);
    const DWORD target_thread = ::GetWindowThreadProcessId(target, nullptr);
    const DWORD self = ::GetCurrentThreadId();
    ::AttachThreadInput(self, fg_thread, TRUE);
    ::AttachThreadInput(self, target_thread, TRUE);
    ::BringWindowToTop(target);
    ::SetForegroundWindow(target);
    ::SetActiveWindow(target);
    ::SetFocus(target);
    ::AttachThreadInput(self, target_thread, FALSE);
    ::AttachThreadInput(self, fg_thread, FALSE);
    ::SystemParametersInfoW(SPI_SETFOREGROUNDLOCKTIMEOUT, 0,
                            reinterpret_cast<PVOID>(old_timeout),
                            SPIF_UPDATEINIFILE | SPIF_SENDCHANGE);
    return ::GetForegroundWindow() == target;
}

bool send_paste_only() {
    send_chord('V');
    return true;
}

bool send_paste_enter() {
    // Focus must already be on the target input; small sleeps give the chat
    // webview a moment to accept the paste before Enter arrives.
    send_chord('V');
    ::Sleep(180);
    send_key(VK_RETURN, true);
    send_key(VK_RETURN, false);
    return true;
}

// ── Windows ──

std::wstring window_text(HWND hwnd) {
    if (!hwnd) return {};
    const int len = ::GetWindowTextLengthW(hwnd);
    if (len <= 0) return {};
    std::wstring out(static_cast<size_t>(len) + 1, L'\0');
    const int got = ::GetWindowTextW(hwnd, out.data(), len + 1);
    out.resize(got > 0 ? static_cast<size_t>(got) : 0);
    return out;
}

std::wstring window_class_name(HWND hwnd) {
    if (!hwnd) return {};
    wchar_t buf[256]{};
    ::GetClassNameW(hwnd, buf, static_cast<int>(std::size(buf)));
    return buf;
}

std::wstring window_process_name(HWND hwnd) {
    if (!hwnd) return {};
    DWORD pid = 0;
    ::GetWindowThreadProcessId(hwnd, &pid);
    if (pid == 0) return {};

    HANDLE snap = ::CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return {};
    std::wstring name;
    PROCESSENTRY32W entry{};
    entry.dwSize = sizeof(entry);
    if (::Process32FirstW(snap, &entry)) {
        do {
            if (entry.th32ProcessID == pid) {
                name = entry.szExeFile;
                break;
            }
        } while (::Process32NextW(snap, &entry));
    }
    ::CloseHandle(snap);
    return name;
}

std::wstring foreground_window_title() { return window_text(::GetForegroundWindow()); }

// ── DPAPI ──

std::string dpapi_protect(std::string_view plaintext) {
    if (plaintext.empty()) return {};

    DATA_BLOB in{};
    in.pbData = reinterpret_cast<BYTE*>(const_cast<char*>(plaintext.data()));
    in.cbData = static_cast<DWORD>(plaintext.size());

    // App-specific entropy: a blob protected here cannot be unprotected by an
    // unrelated program that gets hold of the config file.
    static const char kEntropy[] = "argos-desktop/v1/secret";
    DATA_BLOB entropy{};
    entropy.pbData = reinterpret_cast<BYTE*>(const_cast<char*>(kEntropy));
    entropy.cbData = static_cast<DWORD>(sizeof(kEntropy) - 1);

    DATA_BLOB out{};
    if (!::CryptProtectData(&in, L"Argos Desktop secret", &entropy, nullptr, nullptr,
                            CRYPTPROTECT_UI_FORBIDDEN, &out)) {
        return {};
    }
    std::string blob(reinterpret_cast<char*>(out.pbData), out.cbData);
    ::LocalFree(out.pbData);
    return "dpapi:" + base64_encode(blob);
}

std::optional<std::string> dpapi_unprotect(std::string_view base64_blob) {
    constexpr std::string_view kPrefix = "dpapi:";
    if (base64_blob.substr(0, kPrefix.size()) != kPrefix) return std::nullopt;

    const std::vector<unsigned char> raw = base64_decode(base64_blob.substr(kPrefix.size()));
    if (raw.empty()) return std::nullopt;

    DATA_BLOB in{};
    in.pbData = const_cast<BYTE*>(raw.data());
    in.cbData = static_cast<DWORD>(raw.size());

    static const char kEntropy[] = "argos-desktop/v1/secret";
    DATA_BLOB entropy{};
    entropy.pbData = reinterpret_cast<BYTE*>(const_cast<char*>(kEntropy));
    entropy.cbData = static_cast<DWORD>(sizeof(kEntropy) - 1);

    DATA_BLOB out{};
    if (!::CryptUnprotectData(&in, nullptr, &entropy, nullptr, nullptr,
                              CRYPTPROTECT_UI_FORBIDDEN, &out)) {
        return std::nullopt;
    }
    std::string plain(reinterpret_cast<char*>(out.pbData), out.cbData);
    ::LocalFree(out.pbData);
    return plain;
}

// ── base64 ──

static constexpr char kB64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

std::string base64_encode(std::string_view data) {
    return base64_encode(std::vector<unsigned char>(data.begin(), data.end()));
}

std::string base64_encode(const std::vector<unsigned char>& data) {
    std::string out;
    out.reserve(((data.size() + 2) / 3) * 4);
    size_t i = 0;
    for (; i + 2 < data.size(); i += 3) {
        const unsigned n = (data[i] << 16) | (data[i + 1] << 8) | data[i + 2];
        out.push_back(kB64[(n >> 18) & 63]);
        out.push_back(kB64[(n >> 12) & 63]);
        out.push_back(kB64[(n >> 6) & 63]);
        out.push_back(kB64[n & 63]);
    }
    if (i + 1 == data.size()) {
        const unsigned n = data[i] << 16;
        out.push_back(kB64[(n >> 18) & 63]);
        out.push_back(kB64[(n >> 12) & 63]);
        out.push_back('=');
        out.push_back('=');
    } else if (i + 2 == data.size()) {
        const unsigned n = (data[i] << 16) | (data[i + 1] << 8);
        out.push_back(kB64[(n >> 18) & 63]);
        out.push_back(kB64[(n >> 12) & 63]);
        out.push_back(kB64[(n >> 6) & 63]);
        out.push_back('=');
    }
    return out;
}

std::vector<unsigned char> base64_decode(std::string_view text) {
    static const std::array<int8_t, 256> table = [] {
        std::array<int8_t, 256> t{};
        t.fill(-1);
        for (int i = 0; i < 64; ++i) t[static_cast<unsigned char>(kB64[i])] = static_cast<int8_t>(i);
        return t;
    }();

    std::vector<unsigned char> out;
    out.reserve(text.size() / 4 * 3);
    unsigned buffer = 0;
    int bits = 0;
    for (const char c : text) {
        const int v = table[static_cast<unsigned char>(c)];
        if (v < 0) continue;  // skip '=', newlines and whitespace
        buffer = (buffer << 6) | static_cast<unsigned>(v);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<unsigned char>((buffer >> bits) & 0xFF));
        }
    }
    return out;
}

// ── SHA-256 ──
std::string sha256_hex(std::string_view data) {
    BCRYPT_ALG_HANDLE alg = nullptr;
    if (!BCRYPT_SUCCESS(::BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, nullptr, 0))) {
        return {};
    }
    std::array<unsigned char, 32> digest{};
    if (!BCRYPT_SUCCESS(::BCryptHash(alg, nullptr, 0,
                                     reinterpret_cast<PUCHAR>(const_cast<char*>(data.data())),
                                     static_cast<ULONG>(data.size()), digest.data(),
                                     static_cast<ULONG>(digest.size())))) {
        ::BCryptCloseAlgorithmProvider(alg, 0);
        return {};
    }
    ::BCryptCloseAlgorithmProvider(alg, 0);

    static constexpr char kHex[] = "0123456789abcdef";
    std::string out;
    out.reserve(digest.size() * 2);
    for (const unsigned char b : digest) {
        out.push_back(kHex[b >> 4]);
        out.push_back(kHex[b & 0xF]);
    }
    return out;
}

// ── Image encoding (WIC) ──

namespace {

bool encode_image(const std::filesystem::path& path, int width, int height, const void* bgra_pixels,
                  int stride_bytes, REFGUID container, REFGUID format, int quality) {
    if (width <= 0 || height <= 0 || !bgra_pixels) return false;

    Microsoft::WRL::ComPtr<IWICImagingFactory> factory;
    if (FAILED(::CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                  IID_PPV_ARGS(&factory)))) {
        return false;
    }
    Microsoft::WRL::ComPtr<IWICStream> stream;
    if (FAILED(factory->CreateStream(&stream))) return false;
    if (FAILED(stream->InitializeFromFilename(path.c_str(), GENERIC_WRITE))) return false;

    Microsoft::WRL::ComPtr<IWICBitmapEncoder> encoder;
    if (FAILED(factory->CreateEncoder(container, nullptr, &encoder))) return false;
    if (FAILED(encoder->Initialize(stream.Get(), WICBitmapEncoderNoCache))) return false;

    Microsoft::WRL::ComPtr<IWICBitmapFrameEncode> frame;
    Microsoft::WRL::ComPtr<IPropertyBag2> properties;
    if (FAILED(encoder->CreateNewFrame(&frame, &properties))) return false;

    if (quality > 0 && properties) {
        PROPBAG2 option{};
        option.pstrName = const_cast<LPOLESTR>(L"ImageQuality");
        VARIANT value;
        ::VariantInit(&value);
        value.vt = VT_R4;
        value.fltVal = static_cast<float>(quality) / 100.0f;
        properties->Write(1, &option, &value);
    }

    if (FAILED(frame->Initialize(properties.Get()))) return false;
    if (FAILED(frame->SetSize(static_cast<UINT>(width), static_cast<UINT>(height)))) return false;

    // SetPixelFormat writes back into the GUID it is given, so it must not be
    // handed a read-only global (that faults inside windowscodecs.dll).
    WICPixelFormatGUID actual_format = format;
    if (FAILED(frame->SetPixelFormat(&actual_format))) return false;

    const int stride = stride_bytes > 0 ? stride_bytes : width * 4;
    const UINT buffer_size = static_cast<UINT>(stride) * static_cast<UINT>(height);

    Microsoft::WRL::ComPtr<IWICBitmap> bitmap;
    if (FAILED(factory->CreateBitmapFromMemory(static_cast<UINT>(width), static_cast<UINT>(height),
                                               GUID_WICPixelFormat32bppBGRA,
                                               static_cast<UINT>(stride),
                                               buffer_size,
                                               const_cast<BYTE*>(static_cast<const BYTE*>(bgra_pixels)),
                                               &bitmap))) {
        return false;
    }

    if (FAILED(frame->WriteSource(bitmap.Get(), nullptr))) return false;
    if (FAILED(frame->Commit())) return false;
    return SUCCEEDED(encoder->Commit());
}

}  // namespace

bool save_png(const std::filesystem::path& path, int width, int height, const void* bgra_pixels,
              int stride_bytes) {
    return encode_image(path, width, height, bgra_pixels, stride_bytes, GUID_ContainerFormatPng,
                        GUID_WICPixelFormat32bppBGRA, 0);
}

bool save_jpeg(const std::filesystem::path& path, int width, int height, const void* bgra_pixels,
               int quality, int stride_bytes) {
    return encode_image(path, width, height, bgra_pixels, stride_bytes, GUID_ContainerFormatJpeg,
                        GUID_WICPixelFormat24bppBGR, quality);
}

}  // namespace argos::win
