from fastapi import FastAPI

from app.routes import chat, voice, thought

app = FastAPI(title="Argos Backend")

app.include_router(chat.router, prefix="/api")
app.include_router(voice.router, prefix="/api")
app.include_router(thought.router, prefix="/api")
