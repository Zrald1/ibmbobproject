"""
Environment-based configuration for the Argos backend.

Nothing here is hardcoded — every value comes from the environment
(loaded from a local .env file via python-dotenv during development,
or from real environment variables in production/deployment).

Two independent providers are configured here:

  LLM (chat)  — any OpenAI-compatible /chat/completions endpoint.
                Works unchanged with Cerebras, Groq, OpenAI, Together, etc.
                Switch provider by changing LLM_API_BASE / LLM_API_KEY /
                LLM_MODEL in .env. No code change required.

  STT (voice) — selectable via STT_PROVIDER:
                  "openai"     → any OpenAI-compatible /audio/transcriptions
                                 (Groq Whisper, OpenAI Whisper, …)
                  "assemblyai" → AssemblyAI (sync endpoint, with an automatic
                                 fallback to the v2 upload+poll API)

The two can be mixed freely, e.g. Cerebras for chat + AssemblyAI for voice,
or Groq for both.
"""

import os
from dotenv import load_dotenv

load_dotenv()

# ── LLM (chat) provider — OpenAI-compatible ──
# Cerebras: https://api.cerebras.ai/v1        (e.g. model "qwen-3.8-27b")
# Groq:     https://api.groq.com/openai/v1    (e.g. model "llama-3.3-70b-versatile")
LLM_API_BASE = os.environ.get("LLM_API_BASE", "https://api.cerebras.ai/v1")
LLM_API_KEY = os.environ.get("LLM_API_KEY")  # required at request time
LLM_MODEL = os.environ.get("LLM_MODEL", "qwen-3.8-27b")
LLM_TIMEOUT_SECONDS = int(os.environ.get("LLM_TIMEOUT_SECONDS", "30"))
# Reasoning models (e.g. Cerebras gpt-oss) can burn latency on hidden
# reasoning tokens. "none" keeps replies fast; set to "" to omit the field
# entirely for providers that reject it.
LLM_REASONING_EFFORT = os.environ.get("LLM_REASONING_EFFORT", "none")

# ── Speech-to-text provider ──
# "openai" (default) or "assemblyai"
STT_PROVIDER = (os.environ.get("STT_PROVIDER") or "openai").strip().lower()

# Defaults per provider, all overridable from .env
if STT_PROVIDER == "assemblyai":
    _stt_default_base = "https://sync.assemblyai.com"
    _stt_default_model = "universal-3-5-pro"
else:
    _stt_default_base = "https://api.groq.com/openai/v1"
    _stt_default_model = "whisper-large-v3-turbo"

STT_API_BASE = os.environ.get("STT_API_BASE") or _stt_default_base
STT_MODEL = os.environ.get("STT_MODEL") or _stt_default_model

# An OpenAI-compatible STT provider (e.g. Groq) usually shares the LLM key, so
# fall back to it when STT_API_KEY is blank. AssemblyAI needs its own key.
STT_API_KEY = os.environ.get("STT_API_KEY") or (LLM_API_KEY if STT_PROVIDER == "openai" else None)

STT_LANGUAGE = os.environ.get("STT_LANGUAGE", "en")
STT_TIMEOUT_SECONDS = int(os.environ.get("STT_TIMEOUT_SECONDS", "60"))
