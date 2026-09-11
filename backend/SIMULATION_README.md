# Argos App Simulation

A faithful browser reproduction of the Android app's voice pipeline, used to
verify the real backend (AssemblyAI STT + Cerebras LLM) end-to-end without
building/installing the APK.

## What it mirrors (from `FloatingRobotService.java`)

| App step | Method | Simulation |
|---|---|---|
| Double-tap robot | `onHeadTap()` | double-click canvas / SPACE |
| Capture mic 16 kHz mono 16-bit | `beginRecording()` AudioRecord | Web Audio `ScriptProcessor` |
| 4 s silence auto-stop | amplitude loop | same threshold (`800`) |
| Wrap PCM in 44-byte WAV | `pcmToWav()` | byte-for-byte port |
| POST multipart | `uploadVoiceToBackend()` | `FormData` → `/api/voice` |
| Parse reply | `{"response": "..."}` | same field + `reply` fallback |
| Execute tags | `executeToolTags()` | `[TOOL:EXPR:...]`, `[TOOL:HAND:...]`, `[TOOL:EXPRSEQ:...]` |
| Speak reply | `ttsSpeakJava()` | Web Speech `SpeechSynthesis` |
| Robot expression | `argos_robot.html` | 2D canvas face, same names/colors |

## Run

```powershell
cd backend
python -m http.server 9090 --bind 127.0.0.1
```

Then open <http://127.0.0.1:9090/app_simulation.html>.

- Set **Backend** to `http://13.229.100.183:8000` (default).
- Double-tap the robot (or press SPACE) and speak.
- The conversation log shows the transcription path and the returned expression.

Microphone access requires `localhost` or HTTPS — the local server above is fine.
