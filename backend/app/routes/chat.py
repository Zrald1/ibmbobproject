from fastapi import APIRouter, HTTPException

from app.schemas import ChatRequest, ChatResponse
from app.ai.client import generate_chat_reply

router = APIRouter()


@router.post("/chat", response_model=ChatResponse)
def chat(req: ChatRequest):
    try:
        reply = generate_chat_reply(
            message=req.message,
            history=[m.dict() for m in req.history],
            screen_context=req.screen_context,
        )
    except RuntimeError as e:
        # Configuration problems (e.g. missing API key)
        raise HTTPException(status_code=500, detail=str(e))
    except Exception as e:
        # Network/provider failures
        raise HTTPException(status_code=502, detail=f"AI provider error: {e}")

    return ChatResponse(response=reply)
