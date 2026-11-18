"""Các route AI cho ban tổ chức và quản trị: sinh nội dung sự kiện, phân tích dữ liệu, chiến lược voucher và chatbot agent."""
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
    """Định danh người gọi dùng cho giới hạn tần suất và nhật ký."""
    return actor_of(request, claims)


def _engine_for(profile: str):
    """Engine của lớp có đo lường theo `profile`, trừ khi thuộc tính module `sql_engine` đã bị thay thế (test vá thuộc tính này)."""
    if sql_engine is not analysis_service.LEGACY_ENGINE:
        return sql_engine
    return analysis_service.engine_for(profile)


class GenerateContentRequest(BaseModel):
    """Tiêu đề và các ý chính để sinh mô tả sự kiện."""
    title: str
    bullet_points: str

class GenerateContentResponse(BaseModel):
    """Mô tả và điều khoản sự kiện do AI sinh ra."""
    description: str
    terms_and_conditions: str

# Phân tích dữ liệu và chiến lược voucher đọc dữ liệu sự kiện thông qua mô hình. Admin thấy mọi sự kiện; organizer chỉ thấy
# các sự kiện mình quản lý. Organizer agent chưa có phạm vi dữ liệu nên vẫn chỉ dành cho ADMIN. Sinh nội dung chỉ cần đăng nhập.
# Thứ tự trên ba route này: xác thực / phạm vi, rồi giới hạn tần suất, rồi kiểm tra nội dung yêu cầu.
@router.post("/generate-event-content", response_model=GenerateContentResponse, dependencies=[Depends(require_login)])
def generate_event_content(request: GenerateContentRequest, http: Request, _limit: None = Depends(rate_limit("event_content"))):
    """
    Sinh mô tả sự kiện và điều khoản từ các ý chính (bullet points).
    Dành cho Organizer; chỉ cần đăng nhập.
    """
    result = event_content.generate_event_content(request.title, request.bullet_points,
                                                  actor=_actor(http, getattr(http.state, "claims", None)))
    return GenerateContentResponse(
        description=result.get("description", ""),
        terms_and_conditions=result.get("terms_and_conditions", "")
    )


class AnalyzeRequest(BaseModel):
    """Câu hỏi phân tích dữ liệu bằng ngôn ngữ tự nhiên."""
    question: str

@router.post("/data-analysis")
def analyze_data(request: AnalyzeRequest, http: Request, scope: AnalysisScope = Depends(analysis_scope),
                 _limit: None = Depends(rate_limit("data_analysis"))):
    """Trả lời câu hỏi phân tích dữ liệu trong phạm vi quyền của người gọi; câu hỏi rỗng hoặc quá dài bị từ chối."""
    if not request.question.strip() or len(request.question) > MAX_QUESTION_CHARS:
        raise ai_error(AI_INPUT_INVALID)
    try:
        return analysis_service.analyze(request.question, scope, "analysis", _actor(http, getattr(http.state, "claims", None)),
                                        engine=_engine_for("analysis"))
    except (AiError, HTTPException):
        raise  # ví dụ 503 AI_DB_NOT_CONFIGURED, 422 AI_QUESTION_REFUSED
    except Exception as failure:
        log.error("Data analysis route failed: %s", type(failure).__name__)
        raise ai_error(AI_UNAVAILABLE)

class VoucherStrategyRequest(BaseModel):
    """Mã sự kiện cần đề xuất chiến lược voucher."""
    event_id: str

@router.post("/voucher-strategy")
def voucher_strategy(request: VoucherStrategyRequest, http: Request, scope: AnalysisScope = Depends(analysis_scope),
                     _limit: None = Depends(rate_limit("voucher_strategy"))):
    """Đề xuất chiến lược phát hành voucher cho một sự kiện dựa trên tốc độ bán vé 7 ngày qua; yêu cầu sự kiện nằm trong phạm vi của người gọi."""
    if not scope.allows_event(request.event_id):
        raise HTTPException(status_code=403, detail={"code": "EVENT_NOT_IN_SCOPE", "message": "Bạn không có quyền phân tích sự kiện này."})
    # mã sự kiện được đưa vào nội dung câu hỏi nên chỉ chấp nhận định danh thuần
    if len(request.event_id) > MAX_EVENT_ID_CHARS or not _EVENT_ID.match(request.event_id):
        raise ai_error(AI_INPUT_INVALID)
    try:
        # hỏi SQL engine về tốc độ bán vé của sự kiện
        result = analysis_service.analyze(
            f"Phân tích tốc độ bán vé (sale velocity) của event_id {request.event_id} trong 7 ngày qua "
            "và đề xuất chiến lược phát hành voucher (tên voucher, mã voucher, phần trăm giảm giá, số lượng) "
            "để kích cầu nếu cần thiết.",
            scope, "voucher_strategy", _actor(http, getattr(http.state, "claims", None)), engine=_engine_for("voucher_strategy"),
        )
        return {"strategy": result.get("reply", "Hệ thống đang thu thập thêm dữ liệu để đưa ra đề xuất voucher phù hợp.")}
    except (AiError, HTTPException):
        raise  # ví dụ 503 AI_DB_NOT_CONFIGURED
    except Exception as failure:
        log.error("Voucher strategy route failed: %s", type(failure).__name__)
        raise ai_error(AI_GENERATION_FAILED)


class OrganizerChatRequest(BaseModel):
    """Tin nhắn, lịch sử hội thoại và sự kiện đang xem (nếu có) gửi cho agent của ban tổ chức."""
    message: str
    history: list = []
    event_id: str = None
    event_title: str = None

@router.post("/agent-chat", dependencies=[Depends(require_admin)])
async def organizer_agent_chat(request: OrganizerChatRequest):
    """
    Endpoint chatbot AI Agent dành cho Ban Tổ Chức (Organizer AI Data Assistant)
    với đầy đủ Agent Pipeline, Tool Execution và Response Caching. Hiện chỉ dành cho ADMIN.
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
        raise  # ví dụ 503 AI_DB_NOT_CONFIGURED
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

