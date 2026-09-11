"""
Server-side speech-to-text for /api/voice.

Uses AssemblyAI Sync STT API — sends audio in a single HTTP request
and receives the transcript back in the same response (no polling).
Powered by the Universal-3.5 Pro model.

Audio must be 80ms-120s of 16-bit WAV/PCM. Short clips are padded
with silence to clear AssemblyAI's 80ms minimum instead of failing.
"""

import requests

from app.config import STT_API_KEY, STT_MODEL, STT_TIMEOUT_SECONDS

# AssemblyAI Sync STT requires at least 80ms of audio.
# At 16kHz mono 16-bit, 80ms = 1280 samples = 2560 bytes of PCM data.
_MIN_PCM_BYTES = 2560


def _pad_wav_if_too_short(audio_bytes: bytes) -> bytes:
    """Pad a WAV file with silence so it clears the 80ms minimum.

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

    # Update RIFF chunk size (bytes 4-8) and data chunk size (bytes 40-44).
    new_data_size = _MIN_PCM_BYTES
    new_riff_size = 36 + new_data_size
    padded[4:8] = new_riff_size.to_bytes(4, "little")
    padded[40:44] = new_data_size.to_bytes(4, "little")
    return bytes(padded)


def transcribe_audio(audio_bytes: bytes, filename: str = "audio.wav") -> str:
    if not STT_API_KEY:
        raise RuntimeError(
            "STT_API_KEY is not configured. "
            "Set it in your .env file before uploading voice audio."
        )

    audio_bytes = _pad_wav_if_too_short(audio_bytes)

    url = "https://sync.assemblyai.com/transcribe"
    headers = {
        "Authorization": STT_API_KEY,
        "X-AAI-Model": STT_MODEL,
    }
    files = {"audio": (filename, audio_bytes, "audio/wav")}

    resp = requests.post(url, headers=headers, files=files, timeout=STT_TIMEOUT_SECONDS)
    if resp.status_code >= 400:
        # Surface AssemblyAI's machine-readable error_code when present.
        detail = resp.text
        try:
            body = resp.json()
            detail = body.get("message") or body.get("detail") or detail
        except ValueError:
            pass
        raise RuntimeError(f"Transcription failed ({resp.status_code}): {detail}")

    result = resp.json()
    text = result.get("text", "")
    return text.strip()
