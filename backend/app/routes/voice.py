import json
from typing import Optional

from fastapi import APIRouter, HTTPException, UploadFile, File, Form

from app.schemas import ChatResponse
from app.transcription import transcribe_audio
from app.ai.client import generate_chat_reply

router = APIRouter()


@router.post("/voice", response_model=ChatResponse)
async def voice(
    file: UploadFile = File(...),
    screen_context: Optional[str] = Form(None),
    history: Optional[str] = Form(None),
):
    audio_bytes = await file.read()
    if not audio_bytes:
        raise HTTPException(status_code=400, detail="No audio data received")

    try:
        text = transcribe_audio(audio_bytes, filename=file.filename or "audio.wav")
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Transcription failed: {e}")

    if not text:
        raise HTTPException(status_code=400, detail="Could not transcribe audio (empty result)")

    # `history` is a JSON-encoded array of {"role","content"} objects, sent as
    # a multipart form field by Android (see uploadVoiceToBackend()). Malformed
    # or missing history is treated as "no history" rather than an error.
    history_list = []
    if history:
        try:
            parsed = json.loads(history)
            if isinstance(parsed, list):
                history_list = parsed
        except (ValueError, TypeError):
            history_list = []

    try:
        reply = generate_chat_reply(
            message=text,
            history=history_list,
            screen_context=screen_context,
        )
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"AI provider error: {e}")

    return ChatResponse(response=reply)
