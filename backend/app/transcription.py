"""
Server-side speech-to-text for /api/voice.

Two interchangeable providers, selected by STT_PROVIDER in .env:

  "openai"     — any OpenAI-compatible /audio/transcriptions endpoint.
                 One multipart POST with `file` + `model`, Bearer auth.
                 Works with Groq Whisper, OpenAI Whisper, etc.

  "assemblyai" — AssemblyAI. Tries the synchronous /transcribe endpoint first
                 (single request, no polling); if that isn't available on the
                 account it automatically falls back to the v2 upload + poll
                 flow.

Both return a plain transcript string, so /api/voice does not care which is
in use.
"""

import time

import requests

from app.config import (
    STT_API_BASE,
    STT_API_KEY,
    STT_LANGUAGE,
    STT_MODEL,
    STT_PROVIDER,
    STT_TIMEOUT_SECONDS,
)

# AssemblyAI's sync endpoint requires at least 80ms of audio.
# At 16kHz mono 16-bit that is 1280 samples = 2560 bytes of PCM data.
_MIN_PCM_BYTES = 2560


def transcribe_audio(audio_bytes: bytes, filename: str = "audio.wav") -> str:
    if not STT_API_KEY:
        raise RuntimeError(
            "STT_API_KEY is not configured. Set it in your .env file "
            "(or set LLM_API_KEY when using an OpenAI-compatible STT provider)."
        )
    if STT_PROVIDER == "assemblyai":
        return _transcribe_assemblyai(audio_bytes, filename)
    return _transcribe_openai(audio_bytes, filename)


# ── OpenAI-compatible (Groq Whisper, OpenAI Whisper, …) ──

def _transcribe_openai(audio_bytes: bytes, filename: str) -> str:
    url = f"{STT_API_BASE.rstrip('/')}/audio/transcriptions"
    headers = {"Authorization": f"Bearer {STT_API_KEY}"}
    files = {"file": (filename, audio_bytes, "audio/wav")}
    data = {"model": STT_MODEL}
    if STT_LANGUAGE:
        data["language"] = STT_LANGUAGE

    resp = requests.post(
        url, headers=headers, files=files, data=data, timeout=STT_TIMEOUT_SECONDS
    )
    if resp.status_code >= 400:
        raise RuntimeError(f"Transcription failed ({resp.status_code}): {_error_detail(resp)}")

    return (resp.json().get("text") or "").strip()


# ── AssemblyAI ──

def _transcribe_assemblyai(audio_bytes: bytes, filename: str) -> str:
    audio_bytes = _pad_wav_if_too_short(audio_bytes)

    base = STT_API_BASE.rstrip("/")
    headers = {"Authorization": STT_API_KEY}

    # 1) Synchronous endpoint — one request, no polling, lowest latency.
    try:
        resp = requests.post(
            f"{base}/transcribe",
            headers={**headers, "X-AAI-Model": STT_MODEL},
            files={"audio": (filename, audio_bytes, "audio/wav")},
            timeout=STT_TIMEOUT_SECONDS,
        )
        if resp.status_code < 400:
            return (resp.json().get("text") or "").strip()
        # 404/405 means the sync API isn't enabled for this account/plan —
        # fall through to the v2 upload + poll flow.
        if resp.status_code not in (404, 405):
            raise RuntimeError(f"Transcription failed ({resp.status_code}): {_error_detail(resp)}")
    except requests.RequestException as e:
        raise RuntimeError(f"Transcription request failed: {e}")

    # 2) v2 API — upload the audio, submit a job, poll for the result.
    api_base = "https://api.assemblyai.com/v2"
    upload = requests.post(
        f"{api_base}/upload", headers=headers, data=audio_bytes, timeout=STT_TIMEOUT_SECONDS
    )
    if upload.status_code >= 400:
        raise RuntimeError(f"Upload failed ({upload.status_code}): {_error_detail(upload)}")
    upload_url = upload.json()["upload_url"]

    payload = {"audio_url": upload_url}
    if STT_LANGUAGE:
        payload["language_code"] = STT_LANGUAGE
    job = requests.post(f"{api_base}/transcript", headers=headers, json=payload, timeout=30)
    if job.status_code >= 400:
        raise RuntimeError(f"Transcription submit failed ({job.status_code}): {_error_detail(job)}")
    transcript_id = job.json()["id"]

    deadline = time.time() + STT_TIMEOUT_SECONDS
    while time.time() < deadline:
        poll = requests.get(f"{api_base}/transcript/{transcript_id}", headers=headers, timeout=30)
        poll.raise_for_status()
        result = poll.json()
        status = result.get("status")
        if status == "completed":
            return (result.get("text") or "").strip()
        if status == "error":
            raise RuntimeError(f"Transcription failed: {result.get('error', 'unknown')}")
        time.sleep(0.5)

    raise RuntimeError("Transcription timed out")


# ── Helpers ──

def _pad_wav_if_too_short(audio_bytes: bytes) -> bytes:
    """Pad a PCM WAV with silence so it clears AssemblyAI's 80ms minimum.

    Only handles the standard 44-byte-header PCM WAV that Android sends
    (see FloatingRobotService.pcmToWav). Anything unrecognized is returned
    unchanged and left for the API to accept or reject.
    """
    if len(audio_bytes) < 44:
        return audio_bytes
    if audio_bytes[0:4] != b"RIFF" or audio_bytes[8:12] != b"WAVE":
        return audio_bytes

    data_size = len(audio_bytes) - 44
    if data_size >= _MIN_PCM_BYTES:
        return audio_bytes

    padding = b"\x00" * (_MIN_PCM_BYTES - data_size)
    padded = bytearray(audio_bytes + padding)
    new_riff_size = 36 + _MIN_PCM_BYTES
    padded[4:8] = new_riff_size.to_bytes(4, "little")
    padded[40:44] = _MIN_PCM_BYTES.to_bytes(4, "little")
    return bytes(padded)


def _error_detail(resp) -> str:
    """Pull a readable message out of a provider error response."""
    try:
        body = resp.json()
        return body.get("message") or body.get("detail") or body.get("error") or resp.text
    except ValueError:
        return resp.text
