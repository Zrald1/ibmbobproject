# Argos Backend Integration Guide

This directory is intentionally empty. The backend is **not included** in this repository.

You need to build your own backend server that implements the API endpoints listed below.
The Android app communicates with the backend via HTTP/HTTPS REST calls.

## Architecture Overview

```
┌─────────────────┐         HTTP/HTTPS          ┌─────────────────┐
│  Android App    │  ────────────────────────►  │  Your Backend   │
│                 │                              │                 │
│  WebView        │  POST /api/chat              │  AI Provider    │
│   └─ Three.js   │  POST /api/thought           │  (Bedrock/      │
│      Robot      │  POST /api/transcribe         │   OpenAI/etc)   │
│                 │  POST /api/voice              │                 │
│  FloatingRobot  │  POST /api/auth/device        │  SQLite/        │
│  Service        │  GET  /api/health             │  PostgreSQL     │
│                 │                              │                 │
└─────────────────┘  ◄────────────────────────  └─────────────────┘
```

## How the Robot Connects

The Three.js robot (`assets/argos_robot.html`) runs inside a WebView in `FloatingRobotService.java`.
The robot does **NOT** call the backend directly. The flow is:

1. `FloatingRobotService.java` sends user message to `POST /api/chat`
2. Backend calls AI provider (Bedrock, OpenAI, etc.), returns response + expression tag
3. Java parses the response and calls `robotWebView.evaluateJavascript("ArgosJS.setExpression('HAPPY')", null)`
4. The Three.js robot updates its face/expression/hands accordingly

## Expression Tags

The AI response can include tags that control the robot's expression and hand gestures.
Your backend should instruct the AI to include these tags in responses:

### Expressions (sent via `ArgosJS.setExpression()`)

| Expression | Description |
|-----------|-------------|
| `NEUTRAL` | Default face |
| `HAPPY` | Smiling eyes, green glow |
| `THINKING` | One eye squinted, purple glow, head tilt |
| `TALKING` | Normal eyes, animated mouth |
| `SLEEPING` | Closed eyes, dim glow |
| `SURPRISED` | Wide eyes, open mouth, yellow glow |
| `BLINK` | Quick eye close |
| `WINK` | Left eye close, slight smile |
| `LOVE` | Heart eyes, pink glow |
| `ANGRY` | Narrowed eyes, red glow |
| `SAD` | Downturned mouth, blue glow |
| `CONFUSED` | Uneven eyes, tilted head |
| `EXCITED` | Wide eyes, big smile, bright glow |
| `DIZZY` | Spiral eyes, wobbly |
| `STAR_EYES` | Star-shaped eyes |
| `SCARED` | Wide eyes, trembling |
| `LAUGHING` | Big smile, tears of joy |
| `HIDING_EYES` | Hands covering eyes (privacy mode) |

### Hand Gestures (sent via `ArgosJS.setHandGesture()`)

| Gesture | Description |
|---------|-------------|
| `NONE` | Hands hidden |
| `WAVE` | Both hands waving — greeting |
| `POINT` | Right hand pointing forward |
| `FIST` | Both hands clenched — determined |
| `OPEN` | Both palms forward — welcoming |
| `HEART` | Both hands form heart shape |
| `THUMBS_UP` | Right thumb up |
| `PEACE` | Right hand peace sign (V) |
| `THINK` | Right hand on chin |
| `BELLY` | Both hands on belly — laughing |
| `CHEEKS` | Both hands on cheeks — surprised |
| `DOWN` | Both hands hanging low — sad |
| `RAISED` | Both hands raised — celebrating |
| `CLAP` | Both hands together — clapping |
| `SHRUG` | Both hands out, palms up — "I don't know" |
| `SCRATCH` | Right hand scratching head — confused |
| `TREMBLE` | Both hands trembling — scared |
| `REST` | Default resting position |

### Expression Sequences

The robot can play a sequence of expressions with durations:

```javascript
ArgosJS.playExpressionSequence('[{"expr":"SURPRISED","duration":1.0},{"expr":"HAPPY","duration":2.0}]')
```

## Required API Endpoints

### Authentication (Device-Based — No Email)

#### `POST /api/auth/device`
Request:
```json
{
  "device_id": "unique_device_identifier",
  "device_name": "Pixel 8"
}
```
Response:
```json
{
  "access_token": "jwt_token",
  "token_type": "bearer",
  "device_id": "unique_device_identifier",
  "user": {
    "id": 1,
    "username": "device_abc123"
  }
}
```

### Chat

#### `POST /api/chat`
Headers: `Authorization: Bearer <jwt_token>`
Request:
```json
{
  "message": "What's on my screen?",
  "history": [
    {"role": "user", "content": "Hello"},
    {"role": "assistant", "content": "Hi there!"}
  ]
}
```
Response:
```json
{
  "response": "I can see you're looking at [APP_NAME]. [TOOL:EXPRESSION:HAPPY] [TOOL:HAND:WAVE] Let me help you with that!",
  "expression": "HAPPY",
  "hand_gesture": "WAVE"
}
```

The response can include these tags (parsed by the Android app):
- `[TOOL:EXPRESSION:EXPRESSION_NAME]` — Sets the robot's facial expression
- `[TOOL:HAND:GESTURE_NAME]` — Sets the robot's hand gesture

### Proactive Thoughts

#### `POST /api/thought`
Headers: `Authorization: Bearer <jwt_token>`
Request:
```json
{
  "screen_context": "User is viewing YouTube",
  "current_app": "com.google.android.youtube"
}
```
Response:
```json
{
  "thought": "Nice video choice! [TOOL:EXPRESSION:HAPPY]",
  "expression": "HAPPY"
}
```

### Voice Pipeline

#### `POST /api/transcribe`
Headers: `Authorization: Bearer <jwt_token>`
Request: `multipart/form-data` with `audio` field (WebM/WAV)
Response:
```json
{
  "text": "What time is it?",
  "confidence": 0.95
}
```

#### `POST /api/voice`
Headers: `Authorization: Bearer <jwt_token>`
Request: `multipart/form-data` with `audio` field
Response: Same as `/api/chat` (transcribes, then chats)

### Health Check

#### `GET /api/health`
Response:
```json
{
  "status": "ok",
  "version": "1.0.0"
}
```

## Database Schema (Recommended)

```sql
CREATE TABLE users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL,
  device_id TEXT UNIQUE,
  created_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE ai_providers (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT,
  type TEXT,
  api_url TEXT,
  api_key TEXT,
  api_secret TEXT,
  aws_region TEXT,
  model TEXT,
  max_tokens INTEGER,
  temperature REAL,
  is_active BOOLEAN DEFAULT 0
);
```

## Security Notes

- JWT tokens should expire (recommended: 7 days = 10080 minutes)
- Use HTTPS in production
- Store all secrets in environment variables, never in code
