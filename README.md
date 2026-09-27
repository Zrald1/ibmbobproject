# Argos — AI Companion

Argos is an AI-powered companion that floats on top of your screen as an interactive 3D robot. It listens to you, talks back, reacts with facial expressions and hand gestures, and can act on your device — always triggered by an explicit user command.

Two surfaces share the same robot scene and backend relay:

- **Android** ([`android/`](android/)) — floating overlay service driven by the accessibility APIs
- **Windows desktop** ([`argos-desktop/`](argos-desktop/)) — native Win32/WebView2 overlay that controls IDE AI assistants (IBM Bob, VS Code Copilot, …) through a bridge extension

## Features

- **Floating 3D Robot Overlay** — A Three.js robot rendered in a WebView that floats over any app
- **Voice Conversations** — Double-tap the robot to record, and Argos transcribes, thinks, and speaks the reply aloud
- **Text Chat** — Single-tap to open the chat bubble
- **Expressive Animations** — Facial expressions, articulated neon hands, and word-timed gestures driven by the AI's reply
- **Nanotech Movement** — Argos dissolves into particles, travels, and rebuilds itself in a new spot
- **Screen Awareness** — Reads on-screen text via `AccessibilityService` to give the AI context
- **Tool Execution** — The AI can dial numbers, send SMS, set alarms/timers, open apps, search, and manage notes
- **Persistent Memory** — Conversation history kept across chat sessions
- **Hand Tracking** — Front-camera hand tracking; pinch to grab and drag the robot

## Requirements

- Android 7.0 (API 24) or higher
- Accessibility Service enabled
- Overlay / display permission
- Microphone permission
- A running Argos backend (included in [`backend/`](backend/)) — see [Backend Setup](#backend-setup)

## Getting Started

### Prerequisites

- Android Studio (latest stable)
- Android SDK 36
- Android NDK 27.0.12077973
- JDK 17
- Gradle 8.14.2+ (the wrapper is committed — use `./gradlew`)

### Build

1. Clone the repository:
   ```bash
   git clone https://github.com/Zrald1/ibmbobproject.git
   cd ibmbobproject
   ```

2. Open the `android/` folder in Android Studio.

3. Point the app at your backend:
   - Edit `android/app/src/main/res/values/strings.xml` and set `backend_url` to your server.
   - Add the same host to `android/app/src/main/res/xml/network_security_config.xml`.

4. Build:
   ```bash
   cd android
   ./gradlew assembleRelease
   ```

   Release signing credentials are **not** committed. Provide them via environment
   variables (`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`,
   `ANDROID_KEY_ALIAS`, `ANDROID_KEY_ALIAS_PASSWORD`) or in the git-ignored
   `android/local.properties`. Without them the release build falls back to the
   debug signing key.

## Argos Desktop (Windows)

Native C++/Win32 build (`argos-desktop/`): a transparent, click-through WebView2
window renders the same Three.js robot as Android, plus a chat/control panel
(ImGui) and an HTTP command server (`localhost:47830`).

**Backend relay** — provider API keys never live on the desktop. Chat, STT and
the model fleet are served by your own backend (`java-backend/` Spring Boot, or
`backend/` FastAPI). Settings → "Backend" holds only `base_url` + enable; the
desktop posts `{message, history, screen_context}` to `{base}/api/chat`, and the
model replies `{"tool":"<name>","args":{…}}` to fire desktop tools.

**IDE fleet control** — VS Code-family IDEs (incl. IBM Bob) load the bundled
bridge extension and self-register in `%LOCALAPPDATA%\ArgosDesktop\ide-bridge.d`.
Tools like `ide_dispatch` fan a plan out across **every** registered IDE — each
with its own task — and `ide_status` reports per-IDE progress (workspace, active
file, open tabs, chat providers). Paste-delivery raises each IDE's own window so
multi-window broadcasts don't collide.

**Robot behavior** — right-click opens a floating action menu (onboard this
repo, broadcast a ping to all IDEs, scan for API leaks, git commit, park).
Triple-click parks/unparks; the robot also auto-holds position+size while a
task runs or any panel is open. Listening shows neon hands; speaking plays
word-timed or syllable-fallback lip sync and gesture cycles.

**Repo hygiene tools** — `secret_scan` greps tracked files for leaked API keys
(Cerebras/OpenAI/GitHub/AssemblyAI shapes, masked output) and `git_commit`
stages + commits — designed for the "check before you push" flow. Multi-step or
multi-IDE jobs are gated by `propose_plan` — Argos shows a numbered plan and
waits for your "proceed" before executing.

Build:

```powershell
cd argos-desktop
cmake --preset default && cmake --build build --config Release
bin\argos.exe    # robot appears, chat panel via double-click or tray
```

## Backend Setup

Two backend implementations share the `/api/chat`, `/api/models`, health and
voice contracts:

- [`java-backend/`](java-backend/) — Spring Boot service (the production relay
  Argos Desktop uses; deploys anywhere with `java -jar`)
- [`backend/`](backend/) — FastAPI reference implementation. See
  [`backend/README.md`](backend/README.md) for the full API reference

Provider keys (Cerebras, Gemini, Fable, STT) are set as **environment
variables on the server** (`GPT6_ASTRA_API_KEY`, `GEMINI_FLASH_API_KEY`,
`FABLE_API_KEY`, `STT_API_KEY`) — clients never see them.

Quick start:

```bash
cd backend
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt
cp .env.example .env      # then fill in your API keys
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Endpoints used by the app:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/chat` | POST | Send a chat message to the AI |
| `/api/thought` | POST | Generate a proactive thought bubble |
| `/api/voice` | POST | Voice pipeline (transcribe + chat) |

## Configuration

The app reads the backend URL from string resources. To change it:

1. **strings.xml** — update the `backend_url` value
2. **network_security_config.xml** — update the domain entry
3. **AndroidManifest.xml** — update the `argos.backend_url` metadata value

## Project Structure

```
ibmbobproject/
├── android/
│   ├── app/
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── assets/
│   │   │   │   │   ├── argos_robot.html        # Three.js robot scene
│   │   │   │   │   └── three.min.js
│   │   │   │   ├── java/com/example/argos/
│   │   │   │   │   ├── MainActivity.java            # Entry point, consent, UI
│   │   │   │   │   ├── FloatingRobotService.java    # Overlay service, voice, tools
│   │   │   │   │   ├── ArgosAccessibilityService.java
│   │   │   │   │   └── ...
│   │   │   │   ├── cpp/                             # JNI native code
│   │   │   │   ├── res/                             # Resources, layouts, strings
│   │   │   │   └── AndroidManifest.xml
│   │   │   ├── build.gradle
│   │   │   └── proguard-rules.pro
│   │   ├── build.gradle
│   │   ├── settings.gradle
│   │   └── gradle.properties
│   └── SETUP_GUIDE.md
├── argos-desktop/                                   # Windows desktop (C++/Win32/WebView2)
│   ├── src/
│   │   ├── agent/                                   # Agent loop + backend relay
│   │   ├── bridge/                                  # IDE bridge registry + calls
│   │   ├── tools/                                   # 30+ agent tools (files, IDEs, git, scans)
│   │   ├── ui/                                      # Robot overlay, panel, summary, menu
│   │   └── voice/                                   # STT/TTS engines
│   ├── assets/argos_robot.html                      # Same Three.js scene as Android
│   └── extension-ide/                               # VS Code/Bob bridge extension
├── java-backend/                                    # Spring Boot backend (production)
├── backend/                                         # FastAPI backend
│   ├── app/
│   │   ├── main.py
│   │   ├── config.py                                # Provider configuration
│   │   ├── transcription.py                         # STT providers
│   │   ├── ai/
│   │   │   ├── client.py                            # LLM client
│   │   │   └── prompt.py                            # System prompt + tool tags
│   │   └── routes/                                  # chat, voice, thought
│   ├── .env.example
│   └── README.md
├── scripts/                                         # Signing-secret helpers
├── .github/workflows/                               # CI + tagged release APK
├── .gitignore
├── LICENSE
├── CONTRIBUTING.md
└── README.md
```

## Releases

Pushing a version tag builds and publishes a signed release APK:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

The workflow decodes the keystore from repository secrets, builds the APK,
verifies it is release-signed, and attaches it to a GitHub Release.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

This project is licensed under the MIT License — see [LICENSE](LICENSE) for details.

## Acknowledgments

- Android Accessibility Service documentation
- [three.js](https://threejs.org/) for the robot renderer
- [FastAPI](https://fastapi.tiangolo.com/) for the backend
