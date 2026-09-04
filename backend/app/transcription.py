"""
Server-side speech-to-text for /api/voice.

This exists only because /api/voice must transcribe audio itself — Android
uploads raw WAV audio and never calls a standalone /api/transcribe. On-device
transcription (whisper.cpp via the native agent) is a separate, unrelated
path that this backend does not touch.

Targets any OpenAI-compatible /audio/transcriptions API via
app.config.STT_API_BASE / STT_API_KEY / STT_MODEL. If the leader confirms a
different transcription provider, only this file needs to change.
"""

import requests

from app.config import STT_API_BASE, STT_API_KEY, STT_MODEL, STT_TIMEOUT_SECONDS


def transcribe_audio(audio_bytes: bytes, filename: str = "audio.wav") -> str:
    if not STT_API_KEY:
        raise RuntimeError(
            "STT_API_KEY (or LLM_API_KEY) is not configured. "
            "Set it in your .env file before uploading voice audio."
        )

    url = f"{STT_API_BASE.rstrip('/')}/audio/transcriptions"
    headers = {"Authorization": f"Bearer {STT_API_KEY}"}
    files = {"file": (filename, audio_bytes, "audio/wav")}
    data = {"model": STT_MODEL}

    resp = requests.post(url, headers=headers, files=files, data=data, timeout=STT_TIMEOUT_SECONDS)
    resp.raise_for_status()
    result = resp.json()

    text = result.get("text", "")
    return text.strip()
