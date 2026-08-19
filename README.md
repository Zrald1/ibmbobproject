# Argos — AI Companion for Android

Argos is an AI-powered companion app that floats on top of your screen as an interactive robot. It uses accessibility services to read screen content, interact with apps, and help you navigate your device — all triggered by explicit user commands.

## Features

- **Floating Robot Overlay** — A 3D animated robot that floats on top of any app
- **Chat & Voice Input** — Single-tap to chat, double-tap to record voice
- **Screen Awareness** — Reads on-screen text via AccessibilityService
- **Tool Execution** — AI can dial numbers, send SMS, set alarms, open apps, and more
- **Proactive Speech Bubbles** — Periodic AI-generated comments based on what's on screen
- **Security Hardened** — Root detection, pirate app detection, APK tampering checks, emulator detection
- **Persistent Memory** — SQLite/JSONL memory across chat sessions
- **Hand Tracking** — Native front-camera hand tracking with pinch gesture to drag robot

## Screenshots

> Add screenshots here after first release.

## Requirements

- Android 7.0 (API 24) or higher
- Accessibility Service enabled
- Overlay/display permission
- A backend server running the Argos API (see [Backend Setup](#backend-setup))

## Getting Started

### Prerequisites

- Android Studio (latest stable)
- Android SDK 36
- Android NDK 27.0.12077973
- JDK 24
- Gradle 8.14.2+

### Build

1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/argos.git
   cd argos
   ```

2. Open the `android/` folder in Android Studio.

3. Configure your backend URL:
   - Edit `android/app/src/main/res/values/strings.xml` and replace `your-backend-domain.example.com` with your server URL.
   - Update `android/app/src/main/res/xml/network_security_config.xml` with the same domain.

4. Build the app:
   ```bash
   cd android
   ./gradlew assembleRelease
   ```

### Backend Setup

The backend is not included in this repository. You will need to build and deploy your own backend server that implements the following API endpoints:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/auth/device` | POST | Device-based authentication (no email needed) |
| `/api/chat` | POST | Send a chat message to the AI |
| `/api/thought` | POST | Generate a proactive thought bubble |
| `/api/transcribe` | POST | Transcribe audio to text |
| `/api/voice` | POST | Voice pipeline (transcribe + chat) |
| `/api/health` | GET | Health check |

### Configuration

The app reads the backend URL from manifest metadata or string resources. To change it:

1. **strings.xml**: Update `backend_url` value
2. **network_security_config.xml**: Update the domain entry
3. **AndroidManifest.xml**: Update the `argos.backend_url` metadata value (read by `FloatingRobotService`)

## Project Structure

```
argos/
├── android/
│   ├── app/
│   │   ├── src/
│   │   │   ├── main/
│   │   │   │   ├── java/com/example/argos/
│   │   │   │   │   ├── MainActivity.java          # Entry point, auth, UI
│   │   │   │   │   ├── FloatingRobotService.java   # Overlay service, chat, tools
│   │   │   │   │   ├── ArgosAccessibilityService.java
│   │   │   │   │   ├── SecurityChecker.java         # Root/pirate/tamper detection
│   │   │   │   │   └── ...
│   │   │   │   ├── cpp/                             # JNI native code
│   │   │   │   ├── res/                             # Resources, layouts, strings
│   │   │   │   └── AndroidManifest.xml
│   │   ├── build.gradle
│   │   └── proguard-rules.pro
│   ├── build.gradle
│   ├── settings.gradle
│   └── gradle.properties
├── .github/workflows/                               # CI/CD
├── .gitignore
├── LICENSE
├── CONTRIBUTING.md
├── SECURITY.md
└── README.md
```

## Security

Argos includes multiple layers of security:

- **Root Detection** — Checks for `su` binary, Magisk, SuperSU, root management apps, unlocked bootloader
- **Pirate App Detection** — Scans for Lucky Patcher, cracker apps, billing emulation services
- **APK Tampering** — Verifies APK signature, detects repackaging
- **Emulator Detection** — Blocks emulators and SDK builds
- **Hook Framework Detection** — Detects Xposed, LSPosed, Frida

See [SECURITY.md](SECURITY.md) for vulnerability reporting.

## Privacy

See [android/PRIVACY_POLICY.md](android/PRIVACY_POLICY.md) for the full privacy policy.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

This project is licensed under the MIT License — see [LICENSE](LICENSE) for details.

## Acknowledgments

- OWASP MASTG for mobile security best practices
- Google Play Integrity API documentation
- Android Accessibility Service documentation
