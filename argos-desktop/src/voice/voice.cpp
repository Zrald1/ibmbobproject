#include "voice/voice.h"
#include "voice/tts_engines.h"

#include <windows.h>

#include <mmsystem.h>
#include <sapi.h>
#include <winhttp.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <deque>
#include <format>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <nlohmann/json.hpp>

#include "core/app.h"
#include "core/config.h"
#include "core/log.h"
#include "platform/win_util.h"

namespace argos::voice {

// Live SAPI voice — stored so Esc can purge the queue from another thread.
std::atomic<ISpVoice*> g_voice{nullptr};
// Set by Esc to abort waveOut playback mid-utterance (Murf / Speechmatics path).
std::atomic<bool> g_tts_cancel{false};

namespace {

// ── Mic capture (waveIn, 16 kHz mono PCM16 — what AssemblyAI expects) ──

constexpr int kSampleRate = 16000;
constexpr int kBufBytes = 32 * 1024;
constexpr int kNumBufs = 4;
constexpr size_t kMaxBytes = 16000 * 2 * 180;  // 3-minute safety cap

struct Recorder {
    HWAVEIN hwi = nullptr;
    WAVEHDR hdrs[kNumBufs]{};
    std::vector<BYTE> bufs[kNumBufs];
    std::vector<BYTE> pcm;
    std::mutex mu;
    std::atomic<bool> open{false};
    // Voice-activity tracking for the auto-cut: peak amplitude seen so far
    // and the tick of the last loud buffer.
    std::atomic<bool> heard_speech{false};
    std::atomic<ULONGLONG> last_loud_tick{0};
    std::atomic<ULONGLONG> started_tick{0};

    static void CALLBACK on_data(HWAVEIN, UINT msg, DWORD_PTR self,
                                 DWORD_PTR p1, DWORD_PTR) {
        if (msg != WIM_DATA) return;
        auto* r = reinterpret_cast<Recorder*>(self);
        auto* hdr = reinterpret_cast<WAVEHDR*>(p1);
        // Peak amplitude of this buffer → is someone talking?
        int peak = 0;
        const auto* s = reinterpret_cast<const short*>(hdr->lpData);
        const size_t n = hdr->dwBytesRecorded / 2;
        for (size_t i = 0; i < n; i += 8) {  // sample every 8th — cheap VAD
            int v = s[i] < 0 ? -s[i] : s[i];
            if (v > peak) peak = v;
        }
        if (peak > 1500) {  // ~speech vs room noise
            r->heard_speech = true;
            r->last_loud_tick = ::GetTickCount64();
        }
        {
            std::lock_guard lock(r->mu);
            if (r->pcm.size() < kMaxBytes)
                r->pcm.insert(r->pcm.end(), hdr->lpData,
                              hdr->lpData + hdr->dwBytesRecorded);
        }
        if (r->open) {  // requeue for more audio
            hdr->dwBytesRecorded = 0;
            waveInAddBuffer(r->hwi, hdr, sizeof(*hdr));
        }
    }
};

Recorder g_rec;
std::atomic<int> g_state{0};  // 0 idle, 1 recording, 2 transcribing

bool start_recording() {
    WAVEFORMATEX fmt{};
    fmt.wFormatTag = WAVE_FORMAT_PCM;
    fmt.nChannels = 1;
    fmt.nSamplesPerSec = kSampleRate;
    fmt.wBitsPerSample = 16;
    fmt.nBlockAlign = fmt.nChannels * fmt.wBitsPerSample / 8;
    fmt.nAvgBytesPerSec = kSampleRate * fmt.nBlockAlign;

    if (waveInOpen(&g_rec.hwi, WAVE_MAPPER, &fmt,
                   (DWORD_PTR)&Recorder::on_data, (DWORD_PTR)&g_rec,
                   CALLBACK_FUNCTION) != MMSYSERR_NOERROR)
        return false;

    g_rec.pcm.clear();
    for (int i = 0; i < kNumBufs; ++i) {
        g_rec.bufs[i].assign(kBufBytes, 0);
        auto& h = g_rec.hdrs[i];
        h.lpData = (LPSTR)g_rec.bufs[i].data();
        h.dwBufferLength = kBufBytes;
        waveInPrepareHeader(g_rec.hwi, &h, sizeof(h));
        waveInAddBuffer(g_rec.hwi, &h, sizeof(h));
    }
    g_rec.open = true;
    waveInStart(g_rec.hwi);
    return true;
}

std::vector<BYTE> stop_recording() {
    g_rec.open = false;
    waveInStop(g_rec.hwi);
    waveInReset(g_rec.hwi);
    for (auto& h : g_rec.hdrs)
        if (h.dwFlags & WHDR_PREPARED)
            waveInUnprepareHeader(g_rec.hwi, &h, sizeof(h));
    waveInClose(g_rec.hwi);
    g_rec.hwi = nullptr;
    std::lock_guard lock(g_rec.mu);
    return std::move(g_rec.pcm);
}

// Wrap raw PCM16 in a RIFF/WAVE header.
std::vector<BYTE> make_wav(const std::vector<BYTE>& pcm) {
    std::vector<BYTE> wav(44 + pcm.size());
    auto* p = wav.data();
    auto wr32 = [&](DWORD v) { memcpy(p, &v, 4); p += 4; };
    auto wr16 = [&](WORD v) { memcpy(p, &v, 2); p += 2; };
    memcpy(p, "RIFF", 4); p += 4; wr32((DWORD)(36 + pcm.size()));
    memcpy(p, "WAVE", 4); p += 4;
    memcpy(p, "fmt ", 4); p += 4; wr32(16); wr16(1); wr16(1);
    wr32(kSampleRate); wr32(kSampleRate * 2); wr16(2); wr16(16);
    memcpy(p, "data", 4); p += 4; wr32((DWORD)pcm.size());
    memcpy(p, pcm.data(), pcm.size());
    return wav;
}

// ── HTTP (AssemblyAI uses `Authorization: <key>`, raw) ──

std::optional<std::pair<int, std::string>>
http_request(const std::wstring& verb, const std::string& host,
             unsigned short port, bool https, const std::wstring& path,
             const std::string& auth_key, const std::wstring& extra_headers,
             const void* body, DWORD body_len, int timeout_s) {
    HINTERNET session =
        WinHttpOpen(L"ArgosDesktop/0.1", WINHTTP_ACCESS_TYPE_NO_PROXY,
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

// Crack config().assemblyai.api_base into host/port/scheme/path-prefix.
struct AaiBase {
    std::string host;
    unsigned short port = 443;
    bool https = true;
    std::wstring prefix;  // path prefix, usually empty
};

std::optional<AaiBase> aai_base() {
    const std::wstring w = win::to_wide(config().assemblyai.api_base);
    URL_COMPONENTSW uc{};
    uc.dwStructSize = sizeof(uc);
    wchar_t host[256]{}, path[512]{}, scheme[16]{};
    uc.lpszHostName = host;  uc.dwHostNameLength = 256;
    uc.lpszUrlPath = path;   uc.dwUrlPathLength = 512;
    uc.lpszScheme = scheme;  uc.dwSchemeLength = 16;
    if (!WinHttpCrackUrl(w.c_str(), 0, 0, &uc)) return std::nullopt;
    AaiBase b;
    b.host = win::to_utf8(host);
    b.port = uc.nPort;
    b.https = uc.nScheme == INTERNET_SCHEME_HTTPS;
    b.prefix = std::wstring(path, uc.dwUrlPathLength);
    if (!b.prefix.empty() && b.prefix.back() == L'/') b.prefix.pop_back();
    return b;
}

// Backend STT: no local AssemblyAI key → POST the wav to
// {backend.base_url}/api/transcribe (multipart "file" field). The backend owns
// the STT provider key (STT_API_KEY env on java-backend / FastAPI).
std::pair<bool, std::string> transcribe_backend(const std::vector<BYTE>& wav) {
    const auto& b = config().backend;
    if (!b.enabled || b.base_url.empty())
        return {false, "no AssemblyAI API key and backend off"};

    const std::wstring w = win::to_wide(b.base_url);
    URL_COMPONENTSW uc{};
    uc.dwStructSize = sizeof(uc);
    wchar_t host[256]{}, path[512]{}, scheme[16]{};
    uc.lpszHostName = host;  uc.dwHostNameLength = 256;
    uc.lpszUrlPath = path;   uc.dwUrlPathLength = 512;
    uc.lpszScheme = scheme;  uc.dwSchemeLength = 16;
    if (!WinHttpCrackUrl(w.c_str(), 0, 0, &uc))
        return {false, "bad backend base_url"};
    std::wstring prefix(path, uc.dwUrlPathLength);
    if (!prefix.empty() && prefix.back() == L'/') prefix.pop_back();

    std::string body;
    body += "--argosbnd\r\nContent-Disposition: form-data; name=\"file\";"
            " filename=\"argos.wav\"\r\nContent-Type: audio/wav\r\n\r\n";
    body.append(reinterpret_cast<const char*>(wav.data()), wav.size());
    body += "\r\n--argosbnd--\r\n";

    const std::string auth = b.api_key.empty() ? "" : "Bearer " + b.api_key;
    auto r = http_request(L"POST", win::to_utf8(host), uc.nPort,
                          uc.nScheme == INTERNET_SCHEME_HTTPS,
                          prefix + L"/api/transcribe", auth,
                          L"Content-Type: multipart/form-data; boundary=argosbnd\r\n",
                          body.data(), (DWORD)body.size(), b.timeout_seconds);
    if (!r || r->first != 200) return {false, "backend transcribe failed"};
    auto j = nlohmann::json::parse(r->second, nullptr, false);
    std::string text = j.value("text", "");
    if (text.empty()) text = j.value("transcript", "");
    if (text.empty()) text = j.value("response", "");
    return text.empty() ? std::pair{false, std::string("empty transcript")}
                        : std::pair{true, text};
}

// upload → create transcript → poll. Returns the text or an error string.
std::pair<bool, std::string> transcribe(const std::vector<BYTE>& wav) {
    const auto& cfg = config().assemblyai;
    if (cfg.api_key.empty()) return transcribe_backend(wav);
    auto base = aai_base();
    if (!base) return {false, "bad assemblyai api_base"};

    auto up = http_request(L"POST", base->host, base->port, base->https,
                           base->prefix + L"/v2/upload",
                           cfg.api_key, L"Content-Type: application/octet-stream\r\n",
                           wav.data(), (DWORD)wav.size(), 120);
    if (!up || up->first != 200) return {false, "upload failed"};
    auto upj = nlohmann::json::parse(up->second, nullptr, false);
    std::string audio_url = upj.value("upload_url", "");
    if (audio_url.empty()) return {false, "upload returned no url"};

    nlohmann::json treq{{"audio_url", audio_url}};
    if (!cfg.speech_model.empty())
        treq["speech_models"] = nlohmann::json::array({cfg.speech_model});
    if (cfg.language_detection) treq["language_detection"] = true;
    const std::string tbody = treq.dump();
    auto tr = http_request(L"POST", base->host, base->port, base->https,
                           base->prefix + L"/v2/transcript",
                           cfg.api_key, L"Content-Type: application/json\r\n",
                           tbody.data(), (DWORD)tbody.size(), 30);
    if (!tr || tr->first != 200) return {false, "transcript request failed"};
    auto trj = nlohmann::json::parse(tr->second, nullptr, false);
    std::string id = trj.value("id", "");
    if (id.empty()) return {false, "transcript returned no id"};

    const std::wstring poll =
        base->prefix + L"/v2/transcript/" + win::to_wide(id);
    for (int i = 0; i < 90; ++i) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        auto pr = http_request(L"GET", base->host, base->port, base->https,
                               poll, cfg.api_key, L"", nullptr, 0, 15);
        if (!pr || pr->first != 200) continue;
        auto pj = nlohmann::json::parse(pr->second, nullptr, false);
        std::string st = pj.value("status", "");
        if (st == "completed") {
            std::string text = pj.value("text", "");
            return text.empty() ? std::pair{false, std::string("empty transcript")}
                                : std::pair{true, text};
        }
        if (st == "error")
            return {false, "transcript error: " + pj.value("error", std::string{})};
    }
    return {false, "transcription timed out"};
}

}  // namespace

bool listening() { return g_state == 1; }
bool transcribing() { return g_state == 2; }

// Silence never ends a take — recording runs until a second Caps Lock press
// (or double-click). The only automatic stop is the 3-minute buffer cap, so
// an accidentally-forgotten take can't record forever.
void monitor_take() {
    while (g_state == 1) {
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
        if (g_state != 1) break;
        bool full;
        {
            std::lock_guard lock(g_rec.mu);
            full = g_rec.pcm.size() >= kMaxBytes;
        }
        if (full) {
            log::info("voice: 3-minute take cap reached — ending recording");
            stop_listen();
            return;
        }
    }
}

void start_listen() {
    if (g_state != 0) return;  // already recording or transcribing
    if (config().assemblyai.api_key.empty() &&
        !(config().backend.enabled && !config().backend.base_url.empty())) {
        app().toast("Enable the backend in Settings — STT runs on the server.");
        return;
    }
    g_rec.heard_speech = false;
    g_rec.last_loud_tick = 0;
    g_rec.started_tick = ::GetTickCount64();
    if (!start_recording()) {
        app().toast("Microphone unavailable.");
        return;
    }
    g_state = 1;
    app().robot().notify_activity();
    app().robot().set_listening(true);
    app().toast("Listening… press Caps Lock again or double-click to send.");
    log::info("voice: recording started");
    std::thread([] {
        try {
            monitor_take();
        } catch (const std::exception& e) {
            log::error(std::string("voice monitor threw: ") + e.what());
        } catch (...) {
            log::error("voice monitor threw (unknown exception)");
        }
    }).detach();
}

void stop_listen() {
    if (g_state != 1) return;  // idle or transcribing — nothing to stop

    g_state = 2;
    auto pcm = stop_recording();
    app().robot().notify_activity();
    app().robot().set_listening(false);
    if (pcm.size() < kSampleRate / 4) {  // <125 ms of audio — probably a misclick
        app().toast("Too short to hear anything.");
        g_state = 0;
        return;
    }
    app().robot().set_state("thinking");
    log::info(std::format("voice: captured {} bytes, transcribing", pcm.size()));

    std::thread([wav = make_wav(pcm)] {
        try {
            auto [ok, text] = transcribe(wav);
            if (!ok) {
                log::warn("voice: " + text);
                app().toast("Transcription failed: " + text);
                app().robot().set_state("idle");
                g_state = 0;
                return;
            }
            log::info("voice: heard \"" + text + "\"");
            app().toast("Heard: " + text);
            // Hand the spoken request to the Cerebras brain (agent sets its own
            // thinking/idle states and records the turn in chat history).
            if (!app().agent().ask_async(text)) {
                app().toast("Add a Cerebras key in Settings to act on voice.");
                app().robot().set_state("idle");
            }
        } catch (const std::exception& e) {
            log::error(std::string("voice transcribe threw: ") + e.what());
            app().toast("Transcription failed (internal error).");
            app().robot().set_state("idle");
        } catch (...) {
            log::error("voice transcribe threw (unknown exception)");
            app().robot().set_state("idle");
        }
        g_state = 0;
    }).detach();
}

void toggle_listen() {
    if (g_state == 0) start_listen();
    else stop_listen();
}

// ── TTS (SAPI) — the robot talks the reply out loud ──

std::atomic<bool> g_speaking{false};

bool speaking() { return g_speaking; }

// Utterance queue — speak_async never drops text; a single worker drains it
// in order. Esc clears pending items and purges the in-flight one.
std::mutex g_speak_mu;
std::deque<std::string> g_speech_q;

void stop_speaking() {
    g_tts_cancel = true;  // waveOut poll loop aborts on this (Murf path)
    {
        std::lock_guard lock(g_speak_mu);
        g_speech_q.clear();
    }
    if (ISpVoice* v = g_voice.load()) {
        // Purge the speak queue — WaitUntilDone in the speech thread returns
        // immediately and the normal cleanup path runs (talking off, idle).
        v->Speak(nullptr, SPF_PURGEBEFORESPEAK, nullptr);
    }
    if (g_speaking) log::info("voice: reply cancelled by Esc");
}

void speak_async(const std::string& text) {
    if (text.empty()) return;
    if (!config().assistant.tts_enabled || !config().assistant.speak_replies)
        return;
    {
        std::lock_guard lock(g_speak_mu);
        g_speech_q.push_back(text);
        while (g_speech_q.size() > 8) g_speech_q.pop_front();  // never backlog
    }
    if (g_speaking.exchange(true)) return;  // worker is already draining

    std::thread([] {
        try {
        for (;;) {
            std::string text;
            {
                std::lock_guard lock(g_speak_mu);
                if (g_speech_q.empty()) {
                    // Flip inside the lock so a pusher that arrives between
                    // this check and thread exit still spawns a new worker.
                    g_speaking = false;
                    break;
                }
                text = std::move(g_speech_q.front());
                g_speech_q.pop_front();
            }

            g_tts_cancel = false;
            app().robot().set_talking(true);
            app().robot().set_expression("happy");

            std::string engine = config().assistant.tts_engine;
            if (engine == "voicebox" && !config().voicebox.enabled)
                engine.clear();
            if (engine == "murf" && config().murf.api_key.empty())
                engine.clear();
            if (engine == "speechmatics" && config().speechmatics.api_key.empty())
                engine.clear();
            if (engine.empty()) {
                engine = config().voicebox.enabled ? "voicebox"
                         : !config().murf.api_key.empty() ? "murf"
                         : !config().speechmatics.api_key.empty() ? "speechmatics"
                         : "sapi";
            }

            const std::wstring clean = speech_clean(text);
            const std::string text8 = win::to_utf8(clean);
            bool handled = false;

            if (engine == "voicebox") {
                handled = voicebox_speak(text8);
                if (!handled && !config().murf.api_key.empty()) {
                    murf_speak(text8);
                    handled = true;
                }
            } else if (engine == "murf") {
                murf_speak(text8);
                handled = true;
            } else if (engine == "speechmatics") {
                speechmatics_speak(text8);
                handled = true;
            }

            if (!handled) {
                sapi_speak(clean);
            }
        }
        } catch (const std::exception& e) {
            log::error(std::string("tts worker threw: ") + e.what());
        } catch (...) {
            log::error("tts worker threw (unknown exception)");
        }
        {
            std::lock_guard lock(g_speak_mu);
            g_speaking = false;
            g_speech_q.clear();
        }
        app().robot().set_talking(false);
        app().robot().set_state("idle");
    }).detach();
}

void shutdown() {
    if (g_state == 1) { stop_recording(); }
    g_state = 0;
}

}  // namespace argos::voice
