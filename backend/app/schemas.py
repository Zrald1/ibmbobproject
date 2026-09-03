"""
Request/response models for the three Android-verified endpoints.

Field names match FloatingRobotService.java exactly:
- /api/chat  : {"message", "history":[{role,content}], "screen_context"}
              -> {"response": "..."}
- /api/voice : multipart {"file", "screen_context", "history"} -> same as /api/chat
- /api/thought: {"prompt": "..."} -> {"thought": "..."}

Error responses are NOT modeled here — FastAPI's default HTTPException
handler already returns {"detail": "<message>"}, which is exactly the
first field Android checks for on non-2xx responses (see
FloatingRobotService.java error-parsing comments), so no custom error
schema/handler is needed.
"""

from typing import List, Optional
from pydantic import BaseModel


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    message: str
    history: List[ChatMessage] = []
    screen_context: Optional[str] = None


class ChatResponse(BaseModel):
    response: str


class ThoughtRequest(BaseModel):
    prompt: str


class ThoughtResponse(BaseModel):
    thought: str
