from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from app.services.ai.sql_engine import sql_engine
from app.services.ai.organizer_agent import organizer_agent
from app.core.security import require_admin, require_login
from app.services.ai.scope import AnalysisScope, analysis_scope

router = APIRouter()

class GenerateContentRequest(BaseModel):
    title: str
    bullet_points: str

class GenerateContentResponse(BaseModel):
    description: str
    terms_and_conditions: str

# data analysis and the voucher strategy read event data through the model. An admin sees every event; an organizer sees
# only the events they manage. The organizer agent is not scoped yet and stays ADMIN only. Content generation needs a login only.
@router.post("/generate-event-content", response_model=GenerateContentResponse, dependencies=[Depends(require_login)])
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
def analyze_data(request: AnalyzeRequest, scope: AnalysisScope = Depends(analysis_scope)):
    result = sql_engine.analyze_data(request.question, scope)
    return result

class VoucherStrategyRequest(BaseModel):
    event_id: str

@router.post("/voucher-strategy")
def voucher_strategy(request: VoucherStrategyRequest, scope: AnalysisScope = Depends(analysis_scope)):
    if not scope.allows_event(request.event_id):
        raise HTTPException(status_code=403, detail={"code": "EVENT_NOT_IN_SCOPE", "message": "Bạn không có quyền phân tích sự kiện này."})
    try:
        # ask SQL engine about sale velocity for the event
        result = sql_engine.analyze_data(
            f"Phân tích tốc độ bán vé (sale velocity) của event_id {request.event_id} trong 7 ngày qua "
            "và đề xuất chiến lược phát hành voucher (tên voucher, mã voucher, phần trăm giảm giá, số lượng) "
            "để kích cầu nếu cần thiết.",
            scope,
        )
        return {"strategy": result.get("reply", "Hệ thống đang thu thập thêm dữ liệu để đưa ra đề xuất voucher phù hợp.")}
    except HTTPException:
        raise  # for example 503 AI_DB_NOT_CONFIGURED
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


class OrganizerChatRequest(BaseModel):
    message: str
    history: list = []
    event_id: str = None
    event_title: str = None

@router.post("/agent-chat", dependencies=[Depends(require_admin)])
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
    except HTTPException:
        raise  # for example 503 AI_DB_NOT_CONFIGURED
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

