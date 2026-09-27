# Argos — AI Companion

> A floating 3D robot that lives on your screen, listens, talks, thinks, and acts — on Android and Windows.

Argos is an AI-powered desktop and mobile companion rendered as an interactive Three.js robot. It runs a full **Cerebras agent loop**, drives VS Code-family IDEs through a bridge extension, reads your screen, speaks replies aloud, and dispatches tasks to terminal agents via MCP — all triggered by your explicit command.

Two surfaces share the same robot scene and backend relay:

| Surface | Folder | Stack |
|---|---|---|
| **Windows Desktop** | [`argos-desktop/`](argos-desktop/) | C++20 · Win32 · WebView2 · DirectComposition · Dear ImGui |
| **Android** | [`android/`](android/) | Java · Accessibility API · floating overlay service |

---

## Table of Contents

- [Features](#features)
- [Argos Desktop (Windows)](#argos-desktop-windows)
  - [Architecture](#architecture)
  - [Agent Loop](#agent-loop)
  - [Tool Surface](#tool-surface)
  - [IDE Bridge](#ide-bridge)
  - [MCP Terminal Bridge](#mcp-terminal-bridge)
  - [Phone / Remote Control](#phone--remote-control)
  - [Build](#build)
  - [Dependencies](#dependencies)
- [Android](#android)
- [Backend Setup](#backend-setup)
- [Project Structure](#project-structure)
- [Configuration](#configuration)
- [Releases](#releases)
- [Contributing](#contributing)
- [License](#license)

---

## Features

| Category | Capability |
|---|---|
| **Overlay** | Transparent, click-through always-on-top robot (WebView2 + Three.js, DirectComposition alpha) |
| **Voice** | Double-click to record — AssemblyAI REST transcription → Cerebras agent → TTS reply (Voicebox · Murf · Speechmatics · SAPI) |
| **Chat panel** | Dear ImGui panel with Chat, Transcript, Settings, Tools, Phone, and Log tabs |
| **Agent loop** | Cerebras `POST /v1/chat/completions`, up to 12 tool-call rounds per turn, parallel calls disabled |
| **Sub-agents** | Bitdeer-hosted specialist models (`deepseek-ai/DeepSeek-V4.1-Flash`, `zai-org/GLM-5.3-Flash`, …) delegated by the Cerebras orchestrator |
| **File tools** | `read_file`, `write_file`, `apply_diff`, `insert_content`, `search_files`, `rename_file`, `copy_file`, `delete_file`, `make_dir`, `file_stat` |
| **Shell** | `execute_command` — IDE terminal with shell-integration capture, or `cmd.exe` natively |
| **IDE control** | Open files, jump to line, read/replace editor buffer, diagnostics, symbols, save/save-all, close tabs |
| **AI chat drivers** | `chat.send` into Copilot, Continue, Cline, Roo Code, Cody, Cascade, or any generic chat pane |
| **MCP bridge** | `argos-desktop/mcp-server/argos_bridge.py` — stdio MCP server; lets Gemini CLI, Claude Code, Codex CLI, and aider join the fleet |
| **Phone relay** | Scan a QR to pair your phone; commands arrive via backend relay poll or direct LAN HTTP |
| **Expressions** | Facial animations, neon articulated hands, word-timed lip-sync, gesture cycles |
| **Nanotech move** | Robot dissolves into particles, travels to a new position, and rebuilds |
| **Security** | API keys and tokens DPAPI-encrypted in `%APPDATA%\ArgosDesktop\config.json` — nothing secret in the repo |
| **Android** | Floating overlay + voice + tool execution driven by `AccessibilityService` |

---

## Argos Desktop (Windows)

### Architecture

```
┌─ Cerebras agent loop  (src/agent/agent.cpp)
│     POST /v1/chat/completions + 17 tool schemas
│     up to 12 tool-call rounds · parallel_tool_calls: false
│
├─ IDE bridge  (extension-ide — loopback HTTP, bearer token)
│     VS Code · Cursor · Windsurf · VSCodium · IBM Bob
│
├─ MCP terminal bridge  (mcp-server/argos_bridge.py — stdio)
│     Gemini CLI · Claude Code · Codex CLI · aider
│
├─ Phone — backend relay  (src/link/link_client.cpp)
│     register → poll every 2 s → execute → post result
│
└─ Phone — direct LAN  (src/phone/phone_server.cpp  :47830)
         │
         ▼
   tools::execute(name, args)          src/tools/tools.cpp
         │
    ┌────┴──────┐
    ▼           ▼
 via_bridge   via_native
 WorkspaceEdit  Win32 / std::filesystem
 (undoable,     (no IDE required)
  in editors)
```

```
src/ui/robot_overlay   ←→  assets/argos_robot.html   (Three.js, WebView2 DComp window)
src/ui/summary_overlay ←→  assets/argos_summary.html (glass report, Marked + Mermaid)
src/ui/panel_window        Dear ImGui control panel
```

### Agent Loop

[`src/agent/agent.cpp`](argos-desktop/src/agent/agent.cpp) drives Cerebras with all tool schemas attached (`tool_choice: "auto"`). Each turn runs up to **12 tool-call rounds**, feeding `role:"tool"` results back before the next assistant message. History is kept to `assistant.history_turns` exchanges; orphaned tool results are never left in the context window.

**Sub-agents (Bitdeer):** Each row in Settings → *Bitdeer* defines `{name, model, prompt}` and becomes an `ask_<name>(task, context?)` tool. The Cerebras brain delegates to sub-agents and applies their text output through the real tool set.

**Voice:** Double-click the robot to toggle push-to-talk. [`src/voice/voice.cpp`](argos-desktop/src/voice/voice.cpp) captures 16 kHz mono PCM via `waveIn`, uploads to AssemblyAI REST, and feeds the transcript into `agent().ask_async`. TTS chain: Voicebox → Murf → Speechmatics → SAPI (first available key wins).

### Tool Surface

Full reference: [`argos-desktop/TOOLS.md`](argos-desktop/TOOLS.md)

| Tool | Key params | Notes |
|---|---|---|
| `read_file` | `path`, `offset?`, `limit?` | Line-numbered output |
| `write_file` | `path`, `content` | Creates parent dirs |
| `apply_diff` | `path`, `diff` | `<<<<<<< SEARCH / ======= / >>>>>>> REPLACE` blocks |
| `insert_content` | `path`, `line`, `content` | Zero-based line insert |
| `search_files` | `pattern`, `path?`, `glob?` | Regex content search |
| `execute_command` | `command`, `cwd?`, `timeout_ms?` | Bridge terminal or `cmd.exe` |
| `file_stat` | `path` | exists / kind / size / mtime |
| `rename_file` | `from`, `to` | Move/rename |
| `copy_file` | `from`, `to` | Copy file or tree |
| `make_dir` | `path` | Recursive mkdir |
| `delete_file` | `path`, `confirmed: true` | Bridge → recycle bin |
| `open_in_editor` | `path`, `line?` | Bridge only |
| `get_diagnostics` | `path?` | Bridge only — linter/compiler problems |
| `list_symbols` | `path?` | Bridge only — document outline |
| `ide_status` | — | Workspace, active file, tabs, chat providers |
| `save_file` / `save_all` | — | Bridge only |

All `path` arguments accept absolute paths or paths relative to the IDE workspace root.

### IDE Bridge

The bundled VS Code extension ([`argos-desktop/extension-ide/`](argos-desktop/extension-ide/)) works in **VS Code, Cursor, Windsurf, VSCodium, and IBM Bob**. On activation it starts an HTTP server on `127.0.0.1:47820` (auto-increments if busy) and writes a discovery file to `%APPDATA%\ArgosDesktop\ide-bridge.json`. Argos reads that file and calls `POST /command` with `Authorization: Bearer <token>`.

Full bridge command reference: [`extension-ide/README.md`](argos-desktop/extension-ide/README.md)

Key extras beyond the file tool set:

| Method | Purpose |
|---|---|
| `chat.send` | Deliver a prompt into Copilot, Continue, Cline, Roo, Cody, Cascade, or auto-detect |
| `chat.providers` | List every detected AI assistant in the IDE |
| `editor.replace` | Replace a range or the full buffer via `WorkspaceEdit` (undoable) |
| `file.apply_diff` | SEARCH/REPLACE diff engine — keeps open editors in sync |
| `terminal.run` | Run a command in the IDE's integrated terminal with output capture |
| `symbols.list` | Document outline (functions, classes) |

`chat.send` returns `delivered: "needs-paste"` when the chatbox is webview-based (Continue, Cline, …). Argos then raises the IDE window and synthesizes **Ctrl+V + Enter** via `SendInput` — the paste only lands after the target window is verified foreground by hwnd.

**Install:**
```sh
cd argos-desktop/extension-ide
npm install && npm run compile
npx @vscode/vsce package --allow-missing-repository
# then install the .vsix in your IDE
code --install-extension argos-ide-bridge-0.1.0.vsix
```

### MCP Terminal Bridge

[`argos-desktop/mcp-server/argos_bridge.py`](argos-desktop/mcp-server/argos_bridge.py) is a **zero-dependency stdio MCP server** that lets any terminal-resident AI agent join the Argos fleet.

| Tool | Purpose |
|---|---|
| `argos_get_task` | Claim the pending task Argos dispatched to this terminal |
| `argos_send_reply` | Report the result back into the Argos chatbox |
| `argos_chat` | Post a free-form status line into the chatbox |
| `argos_fleet_status` | List every IDE and terminal Argos currently reaches |

**Wire-up examples:**

```jsonc
// Gemini CLI  (~/.gemini/settings.json)
{ "mcpServers": { "argos": { "command": "python",
    "args": ["<repo>\\argos-desktop\\mcp-server\\argos_bridge.py"] } } }
```
```sh
# Claude Code
claude mcp add argos -- python <repo>\argos-desktop\mcp-server\argos_bridge.py
```
```toml
# Codex CLI  (~/.codex/config.toml)
[mcp_servers.argos]
command = "python"
args = ["<repo>\\argos-desktop\\mcp-server\\argos_bridge.py"]
```

### Phone / Remote Control

Two transports share the same `{method, params}` → `{ok, result|error}` envelope (full reference: [`TOOLS.md § Phone command surface`](argos-desktop/TOOLS.md)):

| Transport | File | Details |
|---|---|---|
| **Backend relay** | `src/link/link_client.cpp` | Registers at `POST <backend>/api/link/register`, polls every 2 s, posts results back. QR payload v2. |
| **Direct LAN** | `src/phone/phone_server.cpp` | HTTP `0.0.0.0:47830`, bearer token. `GET /health` unauthenticated. QR payload v1. |

Pair codes rotate on every session start and expire after 10 minutes. `/api/link/revoke` unpairs all phones instantly.

Notable remote methods: `task.prompt` (full agent loop), `voice.listen` (toggle mic), `robot.move/visible/expression/gesture`, `desktop.status`.

### Build

**Prerequisites:** MSVC 2022+, CMake 3.25+, vcpkg (integrated via `CMakePresets.json`), WebView2 runtime.

```powershell
cd argos-desktop
cmake --preset default
cmake --build build --config Release
bin\argos.exe          # robot overlay appears; double-click to open the panel
```

The post-build step stages `argos.exe` and all assets into `bin/` automatically.

**vcpkg dependencies** (resolved automatically):

| Package | Version |
|---|---|
| `imgui` | 1.91.9 |
| `nlohmann-json` | 3.12.0 |
| `webview2` | 1.0.3595.46 |
| `wil` | 1.0.250325.1 |

System libraries linked: `d3d11`, `dcomp`, `dwmapi`, `winhttp`, `sapi`, `crypt32`, `ws2_32`, `winmm`, `CoreMessaging`, and more — see [`CMakeLists.txt`](argos-desktop/CMakeLists.txt).

### Dependencies

```
C++20 · Win32 · WebView2 (DComp visual hosting) · DirectX 11 · Dear ImGui
nlohmann/json · WIL · Three.js · Marked.js · Mermaid.js
AssemblyAI (STT) · Cerebras (LLM) · Bitdeer (sub-agent models)
Voicebox / Murf / Speechmatics / SAPI (TTS)
```

---

## Android

The Android surface ([`android/`](android/)) is a floating overlay service driven by `AccessibilityService`. It shares the same Three.js robot scene and backend relay as the desktop.

**Requirements:** Android 7.0 (API 24)+, Accessibility Service, Overlay permission, Microphone permission.

**Build:**
```bash
cd android
./gradlew assembleRelease
```

Release signing is provided via environment variables (`ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_ALIAS_PASSWORD`) or `android/local.properties`. Without them the build falls back to the debug key.

---

## Backend Setup

Two backend implementations share the `/api/chat`, `/api/models`, health, voice, and phone-link contracts:

| Backend | Folder | Notes |
|---|---|---|
| **Spring Boot** | [`java-backend/`](java-backend/) | Production relay used by Argos Desktop; `java -jar` anywhere |
| **FastAPI** | [`backend/`](backend/) | Reference implementation — full API reference in [`backend/README.md`](backend/README.md) |

Provider keys (`GPT6_ASTRA_API_KEY`, `GEMINI_FLASH_API_KEY`, `FABLE_API_KEY`, `STT_API_KEY`) are **environment variables on the server only** — clients never see them.

**Quick start (FastAPI):**
```bash
cd backend
python -m venv venv && source venv/bin/activate   # Windows: venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env      # fill in your API keys
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

**Core endpoints:**

| Endpoint | Method | Description |
|---|---|---|
| `/api/chat` | POST | Chat message → LLM reply (with tool JSON) |
| `/api/thought` | POST | Generate a proactive thought bubble |
| `/api/voice` | POST | Voice pipeline: transcribe + chat |
| `/api/link/register` | POST | Desktop registers for phone relay |
| `/api/link/poll` | GET | Desktop polls for pending commands |
| `/api/link/result` | POST | Desktop posts tool results |
| `/api/link/pair` | POST | Phone pairs via QR code |
| `/api/link/status` | POST | Phone reads command results |
| `/api/link/revoke` | POST | Unpair all phones |

---

## Project Structure

```
ibmbobproject/
├── android/                              # Android floating overlay app
│   ├── app/src/main/
│   │   ├── assets/argos_robot.html       # Three.js robot scene
│   │   ├── java/com/example/argos/
│   │   │   ├── MainActivity.java
│   │   │   ├── FloatingRobotService.java
│   │   │   ├── ArgosAccessibilityService.java
│   │   │   └── DesktopLink.java          # phone-side QR pairing + commands
│   │   └── AndroidManifest.xml
│   └── SETUP_GUIDE.md
│
├── argos-desktop/                        # Windows desktop (C++20 / Win32 / WebView2)
│   ├── src/
│   │   ├── agent/agent.cpp               # Cerebras agent loop + Bitdeer sub-agents
│   │   ├── bridge/ide_bridge.cpp         # WinHTTP client for IDE bridge extension
│   │   ├── commands/dispatch.cpp         # shared {method,params} dispatcher
│   │   ├── core/app.cpp                  # message loop, owns all subsystems
│   │   ├── core/config.cpp               # JSON config + DPAPI secret storage
│   │   ├── gfx/device.cpp                # D3D11 device + swap chain
│   │   ├── link/link_client.cpp          # backend relay client
│   │   ├── mcp/mcp_client.cpp            # MCP server client
│   │   ├── phone/phone_server.cpp        # direct LAN HTTP server (:47830)
│   │   ├── tools/tools.cpp               # all tool schemas + execute() dispatch
│   │   ├── ui/panel_ui.cpp               # Dear ImGui panel (Chat/Settings/Phone/…)
│   │   ├── ui/robot_overlay.cpp          # DComp click-through robot window
│   │   ├── ui/summary_overlay.cpp        # glass report overlay
│   │   └── voice/voice.cpp               # waveIn → AssemblyAI → TTS chain
│   ├── assets/
│   │   ├── argos_robot.html              # Three.js robot scene
│   │   ├── argos_summary.html            # glass report renderer (Marked + Mermaid)
│   │   ├── marked.min.js
│   │   ├── mermaid.min.js
│   │   └── three.min.js
│   ├── extension-ide/                    # VS Code / IBM Bob bridge extension
│   │   └── src/
│   │       ├── handlers.ts               # bridge command handlers
│   │       ├── chat.ts                   # AI chatbox provider drivers
│   │       └── edits.ts                  # SEARCH/REPLACE diff engine
│   ├── mcp-server/
│   │   └── argos_bridge.py               # stdio MCP server for terminal agents
│   ├── CMakeLists.txt
│   ├── CMakePresets.json
│   ├── vcpkg.json
│   ├── TOOLS.md                          # full tool + command surface reference
│   └── ONBOARDING.md
│
├── java-backend/                         # Spring Boot production relay
├── backend/                              # FastAPI reference backend
│   ├── app/
│   │   ├── main.py
│   │   ├── config.py
│   │   ├── transcription.py
│   │   ├── ai/client.py
│   │   └── routes/                       # chat, voice, thought, link
│   ├── .env.example
│   └── README.md
│
├── scripts/                              # signing-secret helpers
├── .github/workflows/                    # CI + tagged release APK
├── CONTRIBUTING.md
├── LICENSE
└── README.md
```

---

## Configuration

Argos Desktop stores all settings in `%APPDATA%\ArgosDesktop\config.json`. Secrets (API keys, tokens) are **DPAPI-encrypted** — never stored in plaintext.

Key config sections: `cerebras` (model, temp, reasoning effort, history turns), `bitdeer` (sub-agent rows), `assistant` (name, TTS engine), `phone` (token, relay enable), `backend` (base URL).

The Android app reads backend URL from string resources:
1. `android/app/src/main/res/values/strings.xml` — `backend_url`
2. `android/app/src/main/res/xml/network_security_config.xml` — domain allowlist
3. `android/app/src/main/AndroidManifest.xml` — `argos.backend_url` metadata

---

## Releases

Pushing a version tag triggers the CI workflow, which builds and publishes a signed release APK:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

The workflow decodes the keystore from repository secrets, builds the APK, verifies release signing, and attaches it to a GitHub Release.

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

## License

This project is licensed under the MIT License — see [LICENSE](LICENSE) for details.

---

## Acknowledgments

- [three.js](https://threejs.org/) — robot renderer
- [Dear ImGui](https://github.com/ocornut/imgui) — control panel UI
- [Cerebras](https://cerebras.ai/) — LLM inference
- [AssemblyAI](https://www.assemblyai.com/) — speech-to-text
- [Bitdeer AI](https://www.bitdeer.com/) — sub-agent model hosting
- [FastAPI](https://fastapi.tiangolo.com/) — Python backend
- Android Accessibility Service documentation
