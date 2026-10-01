from fastapi import APIRouter, Depends
from pydantic import BaseModel
from app.db.session import get_db
from app.services.ai.rag_engine import rag_engine
from app.services.ai.booking_agent import booking_agent

router = APIRouter()

class SearchRequest(BaseModel):
    query: str
    top_k: int = 3

@router.post("/chat-search")
async def semantic_search(request: SearchRequest, db = Depends(get_db)):
    # call RAG Engine
    results = rag_engine.search_events(db, request.query, request.top_k)

    # format output
    return {
        "query": request.query,
        "matches": [
            {
                "event_id": r.EventVector.event_id,
                "content": r.EventVector.content,
                "score": 1 - r.distance
            }
            for r in results
        ]
    }

class ChatRequest(BaseModel):
    message: str

@router.post("/smart-booking-chat")
async def smart_booking(request: ChatRequest, db = Depends(get_db)):
    return booking_agent.chat(request.message, db)