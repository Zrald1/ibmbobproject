# Argos — AI Companion for Android

Argos is an AI-powered companion app that floats on top of your screen as an interactive 3D robot. It listens to you, talks back, reacts with facial expressions and hand gestures, and can act on your device through Android's accessibility APIs — always triggered by an explicit user command.

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

## Backend Setup

The backend lives in [`backend/`](backend/) (FastAPI). See
[`backend/README.md`](backend/README.md) for the full API reference and setup.

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
