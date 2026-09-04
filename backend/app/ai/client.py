"""
Thin wrapper around a single LLM provider.

No provider abstraction, no plugin system — just two functions the routes
call directly. Targets any OpenAI-compatible /chat/completions API via
app.config.LLM_API_BASE / LLM_API_KEY / LLM_MODEL.

If the project leader confirms a different provider with a different
request/response shape, only this file needs to change.
"""

import json
from typing import List, Optional

import requests

from app.config import LLM_API_BASE, LLM_API_KEY, LLM_MODEL, LLM_TIMEOUT_SECONDS
from app.ai.prompt import SYSTEM_PROMPT, THOUGHT_SYSTEM_PROMPT


def _describe_screen_context(screen_context: Optional[str]) -> Optional[str]:
    """
    screen_context arrives from Android as a JSON string, e.g.
    {"context":"screen","app":"...","package":"...","visible_text":"...","recent_apps":"..."}
    or {"context":"none","reason":"full_privacy"} / {"context":"none","reason":"app_blocked",...}

    Returns a short plain-text description for the prompt, or None if there's
    nothing usable (privacy mode, blocked app, missing/malformed data) — the
    system prompt already instructs the model not to reference screen content
    when this is absent.
    """
    if not screen_context:
        return None
    try:
        data = json.loads(screen_context)
    except (ValueError, TypeError):
        return None

    if data.get("context") != "screen":
        return None

    app = data.get("app") or "an app"
    visible_text = (data.get("visible_text") or "").strip()
    if visible_text:
        return f'The user is currently in "{app}". Visible on-screen text: "{visible_text}"'
    return f'The user is currently in "{app}".'


def _call_llm(messages: List[dict], max_tokens: int = 400, temperature: float = 0.85) -> str:
    if not LLM_API_KEY:
        raise RuntimeError(
            "LLM_API_KEY is not configured. Set it in your .env file before making AI requests."
        )

    url = f"{LLM_API_BASE.rstrip('/')}/chat/completions"
    headers = {
        "Authorization": f"Bearer {LLM_API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": LLM_MODEL,
        "messages": messages,
        "max_tokens": max_tokens,
        "temperature": temperature,
    }

    resp = requests.post(url, headers=headers, json=payload, timeout=LLM_TIMEOUT_SECONDS)
    resp.raise_for_status()
    data = resp.json()

    try:
        return data["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as e:
        raise RuntimeError(f"Unexpected response shape from LLM provider: {e}")


def generate_chat_reply(
    message: str,
    history: List[dict],
    screen_context: Optional[str] = None,
) -> str:
    """Used by both /api/chat and /api/voice (after transcription)."""
    messages: List[dict] = [{"role": "system", "content": SYSTEM_PROMPT}]

    for item in history:
        role = item.get("role") if isinstance(item, dict) else getattr(item, "role", None)
        content = item.get("content") if isinstance(item, dict) else getattr(item, "content", None)
        if role in ("user", "assistant") and content:
            messages.append({"role": role, "content": content})

    context_note = _describe_screen_context(screen_context)
    user_content = f"[{context_note}]\n{message}" if context_note else message
    messages.append({"role": "user", "content": user_content})

    return _call_llm(messages)


def generate_thought(prompt_text: str) -> str:
    """Used by /api/thought. Independent of chat history by design —
    Android sends a single self-contained prompt per thought."""
    messages = [
        {"role": "system", "content": THOUGHT_SYSTEM_PROMPT},
        {"role": "user", "content": prompt_text},
    ]
    return _call_llm(messages, max_tokens=120, temperature=0.9)
