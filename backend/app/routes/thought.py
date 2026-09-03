from fastapi import APIRouter, HTTPException

from app.schemas import ThoughtRequest, ThoughtResponse
from app.ai.client import generate_thought

router = APIRouter()


@router.post("/thought", response_model=ThoughtResponse)
def thought(req: ThoughtRequest):
    try:
        text = generate_thought(req.prompt)
    except RuntimeError as e:
        raise HTTPException(status_code=500, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"AI provider error: {e}")

    return ThoughtResponse(thought=text)
