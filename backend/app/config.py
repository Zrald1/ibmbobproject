"""
Environment-based configuration for the Argos backend.

Nothing here is hardcoded — every value comes from the environment
(loaded from a local .env file via python-dotenv during development,
or from real environment variables in production/deployment).

Provider: defaults to Groq (console.groq.com) — a genuinely free tier
(no credit card, generous daily request limits) that exposes an
OpenAI-compatible REST API for BOTH chat completions and Whisper
transcription under the same account/API key. This matches the
"OpenAI-compatible" design of app/ai/client.py and app/transcription.py
with zero code changes needed. If the leader later confirms a
different provider, only the values below (and, if the wire shape
differs, app/ai/client.py / app/transcription.py) need to change.
"""

import os
from dotenv import load_dotenv

load_dotenv()

# ── LLM (chat) provider — Groq by default ──
LLM_API_BASE = os.environ.get("LLM_API_BASE", "https://api.groq.com/openai/v1")
LLM_API_KEY = os.environ.get("LLM_API_KEY")  # required at request time, not at import time
LLM_MODEL = os.environ.get("LLM_MODEL", "llama-3.3-70b-versatile")
LLM_TIMEOUT_SECONDS = int(os.environ.get("LLM_TIMEOUT_SECONDS", "30"))

# ── Speech-to-text provider — Groq Whisper by default ──
# Same account/key as the LLM above, so this reuses LLM_API_BASE/LLM_API_KEY
# unless overridden.
STT_API_BASE = os.environ.get("STT_API_BASE") or LLM_API_BASE
STT_API_KEY = os.environ.get("STT_API_KEY") or LLM_API_KEY
STT_MODEL = os.environ.get("STT_MODEL", "whisper-large-v3")
STT_TIMEOUT_SECONDS = int(os.environ.get("STT_TIMEOUT_SECONDS", "60"))
