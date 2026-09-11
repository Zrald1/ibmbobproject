"""
Environment-based configuration for the Argos backend.

Nothing here is hardcoded — every value comes from the environment
(loaded from a local .env file via python-dotenv during development,
or from real environment variables in production/deployment).

Providers:
  - LLM: Cerebras (api.cerebras.ai) — OpenAI-compatible chat completions
  - STT: AssemblyAI (sync.assemblyai.com) — synchronous single-request STT
"""

import os
from dotenv import load_dotenv

load_dotenv()

# ── LLM (chat) provider — Cerebras ──
LLM_API_BASE = os.environ.get("LLM_API_BASE", "https://api.cerebras.ai/v1")
LLM_API_KEY = os.environ.get("LLM_API_KEY")  # required at request time
LLM_MODEL = os.environ.get("LLM_MODEL", "qwen-3.8-27b")
LLM_TIMEOUT_SECONDS = int(os.environ.get("LLM_TIMEOUT_SECONDS", "30"))

# ── Speech-to-text provider — AssemblyAI Sync STT ──
STT_API_KEY = os.environ.get("STT_API_KEY")  # required — AssemblyAI key
STT_MODEL = os.environ.get("STT_MODEL", "universal-3-5-pro")
STT_TIMEOUT_SECONDS = int(os.environ.get("STT_TIMEOUT_SECONDS", "60"))
