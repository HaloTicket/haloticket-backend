from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from app.services.ai.sql_engine import sql_engine
from app.services.ai.organizer_agent import organizer_agent

router = APIRouter()

class GenerateContentRequest(BaseModel):
    title: str
    bullet_points: str

class GenerateContentResponse(BaseModel):
    description: str
    terms_and_conditions: str

@router.post("/generate-event-content", response_model=GenerateContentResponse)
async def generate_event_content(request: GenerateContentRequest):
    """
    Sinh mô tả sự kiện và điều khoản từ bullet points.
    Dành cho Organizer.
    """
    try:
        result = organizer_agent.generate_event_content(request.title, request.bullet_points)
        return GenerateContentResponse(
            description=result.get("description", ""),
            terms_and_conditions=result.get("terms_and_conditions", "")
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


class AnalyzeRequest(BaseModel):
    question: str

@router.post("/data-analysis")
async def analyze_data(request: AnalyzeRequest):
    result = sql_engine.analyze_data(request.question)
    return result

class VoucherStrategyRequest(BaseModel):
    event_id: str

@router.post("/voucher-strategy")
async def voucher_strategy(request: VoucherStrategyRequest):
    try:
        # ask SQL engine about sale velocity for the event
        result = sql_engine.analyze_data(
            f"Phân tích tốc độ bán vé (sale velocity) của event_id {request.event_id} trong 7 ngày qua "
            "và đề xuất chiến lược phát hành voucher (tên voucher, mã voucher, phần trăm giảm giá, số lượng) "
            "để kích cầu nếu cần thiết."
        )
        return {"strategy": result.get("reply", "Hệ thống đang thu thập thêm dữ liệu để đưa ra đề xuất voucher phù hợp.")}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


class OrganizerChatRequest(BaseModel):
    message: str
    history: list = []
    event_id: str = None
    event_title: str = None

@router.post("/agent-chat")
async def organizer_agent_chat(request: OrganizerChatRequest):
    """
    Endpoint Chatbot AI Agent dành cho Ban Tổ Chức (Organizer AI Data Assistant)
    với đầy đủ Agent Pipeline, Tool Execution và Response Caching.
    """
    try:
        result = organizer_agent.chat(
            message=request.message,
            history=request.history,
            event_id=request.event_id,
            event_title=request.event_title
        )
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

