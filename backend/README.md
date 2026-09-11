# Argos Backend

FastAPI backend for the Argos Android app. It receives the user's text or voice,
calls an LLM, and returns a reply plus tool tags that drive the robot's
expressions and gestures.

## Architecture

```
┌─────────────────┐        HTTP/HTTPS         ┌──────────────────────────┐
│  Android App    │  ──────────────────────►  │  Argos Backend (this)    │
│                 │                           │                          │
│  WebView        │  POST /api/chat           │  LLM  (OpenAI-compatible)│
│   └─ Three.js   │  POST /api/thought        │   ├─ Cerebras            │
│      Robot      │  POST /api/voice          │   ├─ Groq                │
│                 │                           │   └─ OpenAI              │
│  FloatingRobot  │                           │                          │
│  Service        │  ◄──────────────────────  │  STT  (selectable)       │
│                 │                           │   ├─ OpenAI-compatible   │
└─────────────────┘                           │   └─ AssemblyAI          │
                                              └──────────────────────────┘
```

The Three.js robot (`android/app/src/main/assets/argos_robot.html`) runs inside a
WebView and does **not** call the backend directly. The flow is:

1. `FloatingRobotService.java` sends the user's message (or recorded audio) to the backend
2. The backend calls the LLM and returns the reply, which may contain tool tags
3. Java parses the tags and calls `robotWebView.evaluateJavascript("ArgosJS.setExpression('HAPPY')", null)`
4. The Three.js robot updates its face, hands and animation accordingly

## Setup

```bash
cd backend
python -m venv venv
source venv/bin/activate          # Windows: venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env              # then fill in your API keys
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Interactive API docs are served at `http://<host>:8000/docs`.

## Configuration

Everything is environment-driven — see [`.env.example`](.env.example) for every
option and provider combination.

### LLM (chat)

Any OpenAI-compatible `/chat/completions` endpoint works; switch provider by
changing three values:

| Provider | `LLM_API_BASE` | `LLM_MODEL` |
|---|---|---|
| Cerebras | `https://api.cerebras.ai/v1` | `qwen-3.8-27b` |
| Groq | `https://api.groq.com/openai/v1` | `llama-3.3-70b-versatile` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |

`LLM_REASONING_EFFORT` controls hidden reasoning tokens (`none` keeps replies
fast). Leave it **blank** for providers that reject the field (e.g. Groq).

### Speech-to-text

Selected with `STT_PROVIDER`:

| `STT_PROVIDER` | Endpoint | Notes |
|---|---|---|
| `openai` (default) | `{STT_API_BASE}/audio/transcriptions` | Groq Whisper / OpenAI Whisper. Falls back to `LLM_API_KEY` when `STT_API_KEY` is blank |
| `assemblyai` | `{STT_API_BASE}/transcribe`, then `v2/upload` + poll | Needs its own key; tries the sync endpoint first and automatically falls back to the v2 flow |

`STT_API_BASE` and `STT_MODEL` are optional — sensible defaults are chosen per
provider.

## API Reference

### `POST /api/chat`

```json
{
  "message": "What's on my screen?",
  "history": [{"role": "user", "content": "Hello"}],
  "screen_context": "{\"context\":\"screen\",\"app\":\"YouTube\"}"
}
```

Response:

```json
{ "response": "I can see you're on YouTube. [TOOL:EXPR:HAPPY] [TOOL:HAND:WAVE]" }
```

### `POST /api/voice`

`multipart/form-data` with:

| Field | Type | Description |
|---|---|---|
| `file` | file | WAV audio (16 kHz mono 16-bit, as sent by the app) |
| `history` | string | JSON-encoded conversation history |
| `screen_context` | string | Optional JSON screen context |

Response: same shape as `/api/chat` (transcribe, then chat).

### `POST /api/thought`

```json
{ "prompt": "The user just opened YouTube." }
```

Response:

```json
{ "thought": "Nice video choice! [TOOL:EXPR:HAPPY]" }
```

## Tool Tags

The AI includes tags inline in its reply; the app strips them for display and
speech, and executes them.

### Expressions — `[TOOL:EXPR:NAME]`

`NEUTRAL`, `HAPPY`, `THINKING`, `TALKING`, `SLEEPING`, `SURPRISED`, `BLINK`,
`WINK`, `LOVE`, `ANGRY`, `SAD`, `CONFUSED`, `EXCITED`, `DIZZY`, `STAR_EYES`,
`SCARED`, `LAUGHING`, `HIDING_EYES`

### Expression sequences — `[TOOL:EXPRSEQ:JSON]`

```
[TOOL:EXPRSEQ:[{"expr":"SURPRISED","duration":1.0},{"expr":"HAPPY","duration":2.0}]]
```

### Hand gestures — `[TOOL:HAND:NAME]`

`NONE`, `WAVE`, `POINT`, `FIST`, `OPEN`, `HEART`, `THUMBS_UP`, `PEACE`, `THINK`,
`BELLY`, `CHEEKS`, `DOWN`, `RAISED`, `CLAP`, `SHRUG`, `SCRATCH`, `TREMBLE`, `REST`

### Action tags

`[TOOL:LOOK]`, `[TOOL:OPEN:package]`, `[TOOL:SEARCH:query]`,
`[TOOL:BROWSER:url]`, `[TOOL:TYPE:text]`, `[TOOL:PASTE:text]`, `[TOOL:COPY:text]`,
`[TOOL:SCHEDULE:HH:MM:task]`, plus the notes file tools (`WRITE_FILE`, `READ_FILE`,
`LIST_FILES`, `EDIT_FILE`, …).

The authoritative tag list lives in [`app/ai/prompt.py`](app/ai/prompt.py) — keep
it in sync with `FloatingRobotService.java`.

## Project Layout

```
backend/
├── app/
│   ├── main.py               # FastAPI app + CORS
│   ├── config.py             # Provider configuration (env-driven)
│   ├── schemas.py            # Request/response models
│   ├── transcription.py      # STT providers
│   ├── ai/
│   │   ├── client.py         # LLM client
│   │   └── prompt.py         # System prompt + tool-tag protocol
│   └── routes/
│       ├── chat.py
│       ├── voice.py
│       └── thought.py
├── .env.example
├── requirements.txt
└── README.md
```

## Development Tools

These are browser-side simulations used to verify the pipeline without building
the APK. They are not part of the deployed backend:

| File | Purpose |
|---|---|
| `app_simulation.html` / `app_simulation.js` | Faithful port of the app's voice pipeline (real mic → WAV → `/api/voice` → TTS) |
| `robot_preview.html` | Renders `argos_robot.html` on a dark backdrop |
| `SIMULATION_README.md` | How the simulation maps to `FloatingRobotService.java` |

## Security Notes

- Never commit `.env` — it is git-ignored; only `.env.example` is tracked
- Use HTTPS in production
- Keep API keys in environment variables, never in code
