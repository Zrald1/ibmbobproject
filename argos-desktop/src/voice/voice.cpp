#include "voice/voice.h"

#include <windows.h>

#include <mmsystem.h>
#include <winhttp.h>

#include <atomic>
#include <chrono>
#include <format>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include <nlohmann/json.hpp>

#include "core/app.h"
#include "core/config.h"
#include "core/log.h"
#include "platform/win_util.h"

namespace argos::voice {
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

    static void CALLBACK on_data(HWAVEIN, UINT msg, DWORD_PTR self,
                                 DWORD_PTR p1, DWORD_PTR) {
        if (msg != WIM_DATA) return;
        auto* r = reinterpret_cast<Recorder*>(self);
        auto* hdr = reinterpret_cast<WAVEHDR*>(p1);
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
             const std::wstring& path, const std::string& auth_key,
             const std::wstring& extra_headers, const void* body,
             DWORD body_len, int timeout_s) {
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
    connection = WinHttpConnect(session, win::to_wide(host).c_str(),
                                INTERNET_DEFAULT_HTTPS_PORT, 0);
    if (!connection) return std::nullopt;
    req = WinHttpOpenRequest(connection, verb.c_str(), path.c_str(), nullptr,
                             WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                             WINHTTP_FLAG_SECURE);
    if (!req) return std::nullopt;

    std::wstring headers = L"Authorization: " + win::to_wide(auth_key) +
                           L"\r\n" + extra_headers;
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

// upload → create transcript → poll. Returns the text or an error string.
std::pair<bool, std::string> transcribe(const std::vector<BYTE>& wav) {
    const auto& cfg = config().assemblyai;
    if (cfg.api_key.empty()) return {false, "no AssemblyAI API key"};

    auto up = http_request(L"POST", "api.assemblyai.com", L"/v2/upload",
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
    auto tr = http_request(L"POST", "api.assemblyai.com", L"/v2/transcript",
                           cfg.api_key, L"Content-Type: application/json\r\n",
                           tbody.data(), (DWORD)tbody.size(), 30);
    if (!tr || tr->first != 200) return {false, "transcript request failed"};
    auto trj = nlohmann::json::parse(tr->second, nullptr, false);
    std::string id = trj.value("id", "");
    if (id.empty()) return {false, "transcript returned no id"};

    const std::wstring poll = L"/v2/transcript/" + win::to_wide(id);
    for (int i = 0; i < 90; ++i) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        auto pr = http_request(L"GET", "api.assemblyai.com", poll, cfg.api_key,
                               L"", nullptr, 0, 15);
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

void toggle_listen() {
    if (g_state == 0) {
        if (config().assemblyai.api_key.empty()) {
            app().toast("Add an AssemblyAI key in Settings first.");
            return;
        }
        if (!start_recording()) {
            app().toast("Microphone unavailable.");
            return;
        }
        g_state = 1;
        app().robot().set_listening(true);
        app().toast("Listening… double-click Argos again to send.");
        log::info("voice: recording started");
        return;
    }
    if (g_state != 1) return;  // transcribing — ignore extra clicks

    g_state = 2;
    auto pcm = stop_recording();
    app().robot().set_listening(false);
    if (pcm.size() < kSampleRate / 4) {  // <125 ms of audio — probably a misclick
        app().toast("Too short to hear anything.");
        g_state = 0;
        return;
    }
    app().robot().set_state("thinking");
    log::info(std::format("voice: captured {} bytes, transcribing", pcm.size()));

    std::thread([wav = make_wav(pcm)] {
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
        g_state = 0;
    }).detach();
}

void shutdown() {
    if (g_state == 1) { stop_recording(); }
    g_state = 0;
}

}  // namespace argos::voice
