from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routes import chat, voice, thought

app = FastAPI(title="Argos Backend")

# CORS — the Android app talks to this over plain HTTP (no browser origin),
# but the browser-based simulation (app_simulation.html) is served from
# localhost and needs Access-Control-Allow-Origin to call /api/* directly.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(chat.router, prefix="/api")
app.include_router(voice.router, prefix="/api")
app.include_router(thought.router, prefix="/api")
