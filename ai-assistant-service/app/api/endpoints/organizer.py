from fastapi import APIRouter
from pydantic import BaseModel
from app.services.ai.sql_engine import sql_engine

router = APIRouter()

class AnalyzeRequest(BaseModel):
    question: str

@router.post("/data-analysis")
async def analyze_data(request: AnalyzeRequest):
    result = sql_engine.analyze_data(request.question)
    return result
