import logging
import re

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel
from app.services.ai.sql_engine import sql_engine
from app.services.ai.organizer_agent import organizer_agent
from app.services.ai import analysis_service, event_content
from app.core.errors import ai_error, AiError, AI_INPUT_INVALID, AI_UNAVAILABLE, AI_GENERATION_FAILED
from app.core.ratelimit import rate_limit, actor_of
from app.core.security import require_admin, require_login
from app.services.ai.scope import AnalysisScope, analysis_scope

log = logging.getLogger("ai.organizer")
router = APIRouter()

MAX_QUESTION_CHARS = 1000
MAX_EVENT_ID_CHARS = 64
_EVENT_ID = re.compile(r"^[A-Za-z0-9_-]+$")


def _actor(request: Request, claims) -> str:
    return actor_of(request, claims)


def _engine_for(profile: str):
    """The engine of the metered layer, unless the module attribute `sql_engine` was replaced (tests patch it)."""
    if sql_engine is not analysis_service.LEGACY_ENGINE:
        return sql_engine
    return analysis_service.engine_for(profile)


class GenerateContentRequest(BaseModel):
    title: str
    bullet_points: str

class GenerateContentResponse(BaseModel):
    description: str
    terms_and_conditions: str

# data analysis and the voucher strategy read event data through the model. An admin sees every event; an organizer sees
# only the events they manage. The organizer agent is not scoped yet and stays ADMIN only. Content generation needs a login only.
# order on these three routes: auth / scope, then the rate limit, then the checks on the body.
@router.post("/generate-event-content", response_model=GenerateContentResponse, dependencies=[Depends(require_login)])
def generate_event_content(request: GenerateContentRequest, http: Request, _limit: None = Depends(rate_limit("event_content"))):
    """
    Sinh mô tả sự kiện và điều khoản từ bullet points.
    Dành cho Organizer.
    """
    result = event_content.generate_event_content(request.title, request.bullet_points,
                                                  actor=_actor(http, getattr(http.state, "claims", None)))
    return GenerateContentResponse(
        description=result.get("description", ""),
        terms_and_conditions=result.get("terms_and_conditions", "")
    )


class AnalyzeRequest(BaseModel):
    question: str

@router.post("/data-analysis")
def analyze_data(request: AnalyzeRequest, http: Request, scope: AnalysisScope = Depends(analysis_scope),
                 _limit: None = Depends(rate_limit("data_analysis"))):
    if not request.question.strip() or len(request.question) > MAX_QUESTION_CHARS:
        raise ai_error(AI_INPUT_INVALID)
    try:
        return analysis_service.analyze(request.question, scope, "analysis", _actor(http, getattr(http.state, "claims", None)),
                                        engine=_engine_for("analysis"))
    except (AiError, HTTPException):
        raise  # for example 503 AI_DB_NOT_CONFIGURED, 422 AI_QUESTION_REFUSED
    except Exception as failure:
        log.error("Data analysis route failed: %s", type(failure).__name__)
        raise ai_error(AI_UNAVAILABLE)

class VoucherStrategyRequest(BaseModel):
    event_id: str

@router.post("/voucher-strategy")
def voucher_strategy(request: VoucherStrategyRequest, http: Request, scope: AnalysisScope = Depends(analysis_scope),
                     _limit: None = Depends(rate_limit("voucher_strategy"))):
    if not scope.allows_event(request.event_id):
        raise HTTPException(status_code=403, detail={"code": "EVENT_NOT_IN_SCOPE", "message": "Bạn không có quyền phân tích sự kiện này."})
    # the id goes into the question text, so only a plain identifier is accepted
    if len(request.event_id) > MAX_EVENT_ID_CHARS or not _EVENT_ID.match(request.event_id):
        raise ai_error(AI_INPUT_INVALID)
    try:
        # ask SQL engine about sale velocity for the event
        result = analysis_service.analyze(
            f"Phân tích tốc độ bán vé (sale velocity) của event_id {request.event_id} trong 7 ngày qua "
            "và đề xuất chiến lược phát hành voucher (tên voucher, mã voucher, phần trăm giảm giá, số lượng) "
            "để kích cầu nếu cần thiết.",
            scope, "voucher_strategy", _actor(http, getattr(http.state, "claims", None)), engine=_engine_for("voucher_strategy"),
        )
        return {"strategy": result.get("reply", "Hệ thống đang thu thập thêm dữ liệu để đưa ra đề xuất voucher phù hợp.")}
    except (AiError, HTTPException):
        raise  # for example 503 AI_DB_NOT_CONFIGURED
    except Exception as failure:
        log.error("Voucher strategy route failed: %s", type(failure).__name__)
        raise ai_error(AI_GENERATION_FAILED)


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

