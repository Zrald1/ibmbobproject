#include "voice/tts_engines.h"

#include <windows.h>
#include <mmsystem.h>
#include <sapi.h>
#include <winhttp.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <format>
#include <sstream>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "core/app.h"
#include "core/config.h"
#include "core/log.h"
#include "platform/win_util.h"

namespace argos::voice {

extern std::atomic<ISpVoice*> g_voice;
extern std::atomic<bool> g_tts_cancel;

namespace {

std::optional<std::pair<int, std::string>>
http_request(const std::wstring& verb, const std::string& host,
             unsigned short port, bool https, const std::wstring& path,
             const std::string& auth_key, const std::wstring& extra_headers,
             const void* body, DWORD body_len, int timeout_s) {
    HINTERNET session =
        WinHttpOpen(L"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    WINHTTP_ACCESS_TYPE_NO_PROXY,
                    WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (!session) return std::nullopt;
    HINTERNET connection = nullptr, req = nullptr;
    struct Guard {
        HINTERNET& a; HINTERNET& b; HINTERNET& c;
        ~Guard() {
            if (c) WinHttpCloseHandle(c);
            if (b) WinHttpCloseHandle(b);
            if (a) WinHttpCloseHandle(a);
        }
    } guard{session, connection, req};

    DWORD t = (DWORD)timeout_s * 1000;
    WinHttpSetTimeouts(session, t, t, t, t);
    connection = WinHttpConnect(session, win::to_wide(host).c_str(), port, 0);
    if (!connection) return std::nullopt;
    req = WinHttpOpenRequest(connection, verb.c_str(), path.c_str(), nullptr,
                             WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                             https ? WINHTTP_FLAG_SECURE : 0);
    if (!req) return std::nullopt;

    std::wstring headers;
    if (!auth_key.empty())
        headers = L"Authorization: " + win::to_wide(auth_key) + L"\r\n";
    headers += extra_headers;
    if (!WinHttpSendRequest(req, headers.c_str(), (DWORD)headers.size(),
                          const_cast<void*>(body), body_len, body_len, 0) ||
        !WinHttpReceiveResponse(req, nullptr))
        return std::nullopt;

    DWORD status = 0, sz = sizeof(status);
    WinHttpQueryHeaders(req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                        WINHTTP_HEADER_NAME_BY_INDEX, &status, &sz,
                        WINHTTP_NO_HEADER_INDEX);
    std::string out;
    for (;;) {
        DWORD avail = 0;
        if (!WinHttpQueryDataAvailable(req, &avail) || avail == 0) break;
        std::string chunk(avail, '\0');
        DWORD got = 0;
        if (!WinHttpReadData(req, chunk.data(), avail, &got)) break;
        out.append(chunk, 0, got);
    }
    return std::pair{(int)status, out};
}

const BYTE* wav_chunk(const std::vector<BYTE>& wav, const char tag[4], DWORD* out_size) {
    if (wav.size() < 12 || memcmp(wav.data(), "RIFF", 4) || memcmp(wav.data() + 8, "WAVE", 4))
        return nullptr;
    size_t off = 12;
    while (off + 8 <= wav.size()) {
        const BYTE* hdr = wav.data() + off;
        DWORD sz = *reinterpret_cast<const DWORD*>(hdr + 4);
        const size_t avail = wav.size() - off - 8;
        if (sz > avail) sz = (DWORD)avail;
        if (!memcmp(hdr, tag, 4)) {
            *out_size = sz;
            return hdr + 8;
        }
        off += 8 + sz + (sz & 1);
    }
    return nullptr;
}

void play_wav(const std::vector<BYTE>& wav, const std::string& text8, double duration_hint_s) {
    DWORD fmt_sz = 0, data_sz = 0;
    const BYTE* fmt_p = wav_chunk(wav, "fmt ", &fmt_sz);
    const BYTE* data = wav_chunk(wav, "data", &data_sz);
    if (!fmt_p || fmt_sz < 16 || !data || !data_sz) return;
    const auto* fmt = reinterpret_cast<const WAVEFORMATEX*>(fmt_p);
    if (fmt->wFormatTag != WAVE_FORMAT_PCM && fmt->wFormatTag != 0xFFFE) return;

    std::vector<std::pair<double, std::string>> sched;
    double total_w = 0;
    {
        std::istringstream ws(text8);
        std::string w;
        while (ws >> w) { sched.push_back({0.0, w}); total_w += w.size() + 1.0; }
    }
    double dur = duration_hint_s;
    if (dur <= 0)
        dur = (double)data_sz / (double)(fmt->nSamplesPerSec * fmt->nChannels * (fmt->wBitsPerSample / 8));
    {
        double acc = 0;
        for (auto& s : sched) {
            s.first = acc;
            acc += dur * (s.second.size() + 1.0) / total_w;
        }
    }

    WAVEFORMATEX out_fmt = *fmt;
    HWAVEOUT hwo = nullptr;
    if (waveOutOpen(&hwo, WAVE_MAPPER, &out_fmt, 0, 0, CALLBACK_NULL) != MMSYSERR_NOERROR)
        return;
    WAVEHDR hdr{};
    hdr.lpData = const_cast<LPSTR>(reinterpret_cast<LPCSTR>(data));
    hdr.dwBufferLength = data_sz;
    if (waveOutPrepareHeader(hwo, &hdr, sizeof(hdr)) != MMSYSERR_NOERROR ||
        waveOutWrite(hwo, &hdr, sizeof(hdr)) != MMSYSERR_NOERROR) {
        waveOutClose(hwo);
        return;
    }

    const ULONGLONG t0 = ::GetTickCount64();
    size_t wi = 0;
    while (!(hdr.dwFlags & WHDR_DONE)) {
        if (g_tts_cancel.load()) {
            waveOutReset(hwo);
            break;
        }
        const double el = (::GetTickCount64() - t0) / 1000.0;
        while (wi < sched.size() && sched[wi].first <= el) {
            app().robot().on_speak_word((int)wi, (int)wi, sched[wi].second);
            ++wi;
        }
        Sleep(15);
    }
    waveOutUnprepareHeader(hwo, &hdr, sizeof(hdr));
    waveOutClose(hwo);
}

}  // namespace

std::wstring speech_clean(const std::string& md) {
    std::string out;
    out.reserve(md.size());
    std::istringstream ss(md);
    std::string line;
    bool in_code = false;
    while (std::getline(ss, line)) {
        if (line.rfind("```", 0) == 0) {
            if (!in_code) out += " code block skipped. ";
            in_code = !in_code;
            continue;
        }
        if (in_code || line.empty()) continue;
        std::string r;
        for (size_t i = 0; i < line.size(); ++i) {
            const char c = line[i];
            if (c == '*' || c == '_' || c == '#' || c == '`' || c == '~') continue;
            if (c == '[') continue;
            if (c == ']') {
                if (i + 1 < line.size() && line[i + 1] == '(') {
                    const size_t e = line.find(')', i);
                    i = (e == std::string::npos) ? line.size() : e;
                }
                continue;
            }
            r += c;
        }
        size_t b = r.find_first_not_of(" \t>-+");
        if (b != std::string::npos) {
            out += r.substr(b);
            out += ". ";
        }
    }
    std::string flat;
    flat.reserve(out.size());
    bool ws = false;
    for (char c : out) {
        if (isspace((unsigned char)c)) { ws = true; continue; }
        if (ws && !flat.empty()) flat += ' ';
        ws = false;
        flat += c;
    }
    std::wstring w;
    w.reserve(flat.size());
    for (const wchar_t c : win::to_wide(flat)) {
        if ((c >= 0xD800 && c <= 0xDFFF) || c == 0x200D ||
            (c >= 0x2190 && c <= 0x21FF) ||
            (c >= 0x2600 && c <= 0x27BF) ||
            (c >= 0x2B00 && c <= 0x2BFF) ||
            (c >= 0xFE00 && c <= 0xFE0F)) continue;
        w += c;
    }
    return w;
}

std::wstring xml_escape(std::wstring_view s) {
    std::wstring w;
    w.reserve(s.size());
    for (const wchar_t c : s) {
        switch (c) {
            case L'&': w += L"&amp;"; break;
            case L'<': w += L"&lt;"; break;
            case L'>': w += L"&gt;"; break;
            default: w += c;
        }
    }
    return w;
}

bool voicebox_speak(const std::string& text8) {
    const auto& vb = config().voicebox;
    if (!vb.enabled) return false;

    const std::string& profile = vb.profile;

    URL_COMPONENTSW uc{};
    uc.dwStructSize = sizeof(uc);
    wchar_t host[256]{}, path[512]{}, scheme[16]{};
    uc.lpszHostName = host;  uc.dwHostNameLength = 256;
    uc.lpszUrlPath = path;   uc.dwUrlPathLength = 512;
    uc.lpszScheme = scheme;  uc.dwSchemeLength = 16;
    if (!WinHttpCrackUrl(win::to_wide(vb.base_url).c_str(), 0, 0, &uc)) return false;

    nlohmann::json req = {{"profile", profile}, {"text", text8}};
    const std::string payload = req.dump();
    const std::wstring hdrs = L"Content-Type: application/json\r\n";

    auto resp = http_request(L"POST", win::to_utf8(host), uc.nPort,
                             uc.nScheme == INTERNET_SCHEME_HTTPS,
                             std::wstring(path, uc.dwUrlPathLength) + L"/speak", "", hdrs,
                             payload.data(), (DWORD)payload.size(), 4);
    if (!resp || resp->first != 200) {
        log::info("tts: voicebox offline or unavailable at " + vb.base_url);
        return false;
    }
    log::info(std::format("tts: voicebox playing profile {}", profile));
    return true;
}

void murf_speak(const std::string& text8) {
    const auto& cfg = config().murf;
    std::string voice_id = cfg.voice;
    if (voice_id.find('-') == std::string::npos) voice_id = "en-US-" + voice_id;

    URL_COMPONENTSW uc{};
    uc.dwStructSize = sizeof(uc);
    wchar_t host[256]{}, path[512]{}, scheme[16]{};
    uc.lpszHostName = host;  uc.dwHostNameLength = 256;
    uc.lpszUrlPath = path;   uc.dwUrlPathLength = 512;
    uc.lpszScheme = scheme;  uc.dwSchemeLength = 16;
    if (!WinHttpCrackUrl(win::to_wide(cfg.base_url).c_str(), 0, 0, &uc)) return;

    nlohmann::json req = {
        {"text", text8}, {"voiceId", voice_id}, {"format", "WAV"},
        {"channelType", "MONO"}, {"sampleRate", 24000}, {"modelVersion", "GEN2"},
        {"encodeAsBase64", true}, {"rate", std::clamp(cfg.rate, -50, 50)},
        {"pitch", std::clamp(cfg.pitch, -50, 50)}
    };
    const std::string payload = req.dump();
    const std::wstring hdrs = L"api-key: " + win::to_wide(cfg.api_key) + L"\r\nContent-Type: application/json\r\n";

    auto resp = http_request(L"POST", win::to_utf8(host), uc.nPort,
                             uc.nScheme == INTERNET_SCHEME_HTTPS,
                             std::wstring(path, uc.dwUrlPathLength) + L"/speech/generate", "", hdrs,
                             payload.data(), (DWORD)payload.size(), 90);
    if (!resp || resp->first != 200) return;
    auto rj = nlohmann::json::parse(resp->second, nullptr, false);
    std::vector<BYTE> wav;
    std::string b64 = rj.value("encodedAudio", "");
    if (!b64.empty()) {
        wav = win::base64_decode(b64);
    } else {
        std::string file_url = rj.value("audioFile", "");
        if (file_url.empty()) return;
        URL_COMPONENTSW fuc{};
        fuc.dwStructSize = sizeof(fuc);
        wchar_t fh[256]{}, fp[1024]{}, fs[16]{};
        fuc.lpszHostName = fh;  fuc.dwHostNameLength = 256;
        fuc.lpszUrlPath = fp;   fuc.dwUrlPathLength = 1024;
        fuc.lpszScheme = fs;    fuc.dwSchemeLength = 16;
        if (!WinHttpCrackUrl(win::to_wide(file_url).c_str(), 0, 0, &fuc)) return;
        auto dl = http_request(L"GET", win::to_utf8(fh), fuc.nPort,
                               fuc.nScheme == INTERNET_SCHEME_HTTPS,
                               std::wstring(fp, fuc.dwUrlPathLength), "", L"", nullptr, 0, 60);
        if (!dl || dl->first != 200) return;
        wav.assign(dl->second.begin(), dl->second.end());
    }
    if (wav.size() < 44) return;
    double dur = rj.value("audioDurationInSeconds", 0.0);
    play_wav(wav, text8, dur);
}

void speechmatics_speak(const std::string& text8) {
    const auto& cfg = config().speechmatics;
    URL_COMPONENTSW uc{};
    uc.dwStructSize = sizeof(uc);
    wchar_t host[256]{}, path[512]{}, scheme[16]{};
    uc.lpszHostName = host;  uc.dwHostNameLength = 256;
    uc.lpszUrlPath = path;   uc.dwUrlPathLength = 512;
    uc.lpszScheme = scheme;  uc.dwSchemeLength = 16;
    if (!WinHttpCrackUrl(win::to_wide(cfg.base_url).c_str(), 0, 0, &uc)) return;

    std::wstring url = std::wstring(path, uc.dwUrlPathLength) + L"/generate/" +
                       win::to_wide(cfg.voice) + L"?output_format=wav_16000";
    std::string payload = nlohmann::json{{"text", text8}}.dump();
    auto resp = http_request(L"POST", win::to_utf8(host), uc.nPort,
                             uc.nScheme == INTERNET_SCHEME_HTTPS, url,
                             "Bearer " + cfg.api_key, L"Content-Type: application/json\r\n",
                             payload.data(), (DWORD)payload.size(), 60);
    if (!resp || resp->first != 200) return;
    std::vector<BYTE> wav(resp->second.begin(), resp->second.end());
    if (wav.size() < 44) return;
    play_wav(wav, text8, 0.0);
}

void sapi_speak(const std::wstring& clean) {
    const std::wstring wtext = xml_escape(clean);
    HRESULT hr = ::CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    ISpVoice* v = nullptr;
    if (SUCCEEDED(::CoCreateInstance(CLSID_SpVoice, nullptr, CLSCTX_ALL, IID_PPV_ARGS(&v)))) {
        g_voice = v;
        v->SetRate((long)std::clamp(config().assistant.tts_rate, -10, 10));
        v->SetVolume((USHORT)std::clamp(config().assistant.tts_volume, 0, 100));
        v->SetInterest(SPEI_WORD_BOUNDARY | SPEI_END_INPUT_STREAM,
                       SPEI_WORD_BOUNDARY | SPEI_END_INPUT_STREAM);
        v->Speak(wtext.c_str(), SPF_ASYNC, nullptr);

        int word_index = 0;
        // For SPEI_WORD_BOUNDARY: lParam = character offset in the string,
        // wParam = word length in characters. We extract the word slice directly.
        auto feed_words = [&](SPEVENT* ev, ULONG got) {
            for (ULONG i = 0; i < got; ++i) {
                if (ev[i].eEventId != SPEI_WORD_BOUNDARY) continue;
                std::string word;
                const ULONG char_off = static_cast<ULONG>(ev[i].lParam);
                const ULONG char_len = static_cast<ULONG>(ev[i].wParam);
                if (char_off < wtext.size()) {
                    const ULONG safe_len = std::min(char_len,
                                                    static_cast<ULONG>(wtext.size() - char_off));
                    word = win::to_utf8(wtext.substr(char_off, safe_len));
                }
                if (word.empty()) word = "word";
                app().robot().on_speak_word(word_index, word_index, word);
                ++word_index;
            }
        };

        SPEVENT ev[16];
        for (;;) {
            ULONG got = 0;
            while (v->GetEvents(16, ev, &got) == S_OK && got) feed_words(ev, got);
            if (v->WaitUntilDone(40) == S_OK) {
                ULONG tail = 0;
                while (v->GetEvents(16, ev, &tail) == S_OK && tail) feed_words(ev, tail);
                break;
            }
        }
        g_voice = nullptr;
        v->Release();
    }
    if (SUCCEEDED(hr)) ::CoUninitialize();
}

}  // namespace argos::voice
