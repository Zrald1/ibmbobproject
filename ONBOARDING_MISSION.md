You are the onboarding engineer for this repository — a new developer must become productive today.

Argos (a desktop companion agent) did this quick recon:

repo root: c:\Users\Zrald\ibmbobproject

Top level:
  [dir]  .devin
  [dir]  .git
  [dir]  .github
  [file] .gitignore
  [dir]  .vscode
  [dir]  android
  [dir]  argos-desktop
  [dir]  backend
  [file] CONTRIBUTING.md
  [dir]  cpp
  [dir]  java-backend
  [file] LICENSE
  [file] nul
  [file] README.md
  [dir]  scripts

File types: .cpp=23 .h=21 .py=15 (no ext)=14 .js=9 .md=9 .json=8 .txt=7 .properties=7 .html=6 .ts=5 .map=5

--- README.md (excerpt) ---
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
  


Your mission (use agent mode, subagents and document understanding as needed):
1. Analyze the codebase: purpose, tech stack, architecture, entry points, data flow, key modules.
2. Write AGENTS.md at the repo root — persistent project context for AI agents: build/test commands, conventions, key directories.
3. Write ONBOARDING.md at the repo root — start with a ```mermaid fenced block containing a mermaid "graph TD" architecture diagram of the project structure (top-level modules and how they connect), then: architecture map, module tour, exact setup/build/test commands, 3 suggested first tasks ranked by difficulty, an "unwritten conventions and gotchas" section (error-handling style, where validation lives, test patterns, naming, known traps), and a "who to ask" section mapping key modules to their top contributors.
4. If setup needs steps (dependency install, env vars, tooling), create a script (scripts/setup.ps1 or scripts/setup.sh) that performs them.
5. Append repo-specific items to ONBOARDING_CHECKLIST.md — Argos already wrote the standard items; keep its checkbox lines intact.
6. Finish with a 5-sentence spoken-style summary: what the project is, the stack, how to build and test it, and the recommended first task.