"""Phân tích dữ liệu và chiến lược voucher qua lớp mô hình có đo lường.

Mỗi profile ('analysis', 'voucher_strategy') có SQLEngine riêng dựng với `llm=` là một runnable có đo lường, nên hai
route dùng `ai_invoke` (giới hạn, hạn chót, ghi nhận sử dụng). Module này ánh xạ kết quả của engine sang mã lỗi AiError.
"""
import contextvars
import logging
import threading
from typing import Optional

from langchain_core.runnables import RunnableLambda

from app.core.errors import ai_error, AI_QUESTION_REFUSED, AI_UNAVAILABLE
from app.services.ai.ai_call import metered_llm
from app.services.ai.scope import AnalysisScope
from app.services.ai.sql_engine import SQLEngine, sql_engine as LEGACY_ENGINE

log = logging.getLogger("ai.analysis")

PROFILES = ("analysis", "voucher_strategy")
# các mã analyze_data đặt vào result["error"] khi lỗi do hệ thống (không phải do câu hỏi)
SYSTEM_FAILURES = frozenset({"NO_ALLOWED_RELATIONS", "SCHEMA_UNAVAILABLE", "ANALYSIS_FAILED"})

_actor = contextvars.ContextVar("ai_analysis_actor", default=None)
_engines: dict = {}
_lock = threading.Lock()


def _runnable(profile: str):
    """Bọc đối tượng gọi mô hình có đo lường thành Runnable để dùng được trong chuỗi `prompt | llm`."""
    # `prompt | llm` cần một Runnable nên đối tượng có đo lường được bọc lại; người gọi của yêu cầu lấy từ biến ngữ cảnh
    def call(prompt_value):
        """Chuyển prompt thành tin nhắn và gọi mô hình với định danh người gọi hiện tại."""
        return metered_llm(profile, _actor.get()).invoke(prompt_value.to_messages())

    return RunnableLambda(call)


def engine_for(profile: str) -> SQLEngine:
    """SQLEngine riêng của profile (tạo lười, dùng lại); ném `ValueError` nếu profile không hợp lệ."""
    if profile not in PROFILES:
        raise ValueError(f"unknown analysis profile: {profile}")
    with _lock:
        engine = _engines.get(profile)
        if engine is None:
            engine = SQLEngine(llm=_runnable(profile))
            _engines[profile] = engine
        return engine


def reset_for_tests() -> None:
    """Xóa các engine đã tạo; chỉ dùng trong kiểm thử."""
    with _lock:
        _engines.clear()


def analyze(question: str, scope: Optional[AnalysisScope], profile: str = "analysis", actor: Optional[str] = None,
            engine=None) -> dict:
    """Chạy câu hỏi và trả về dict thành công của engine. `HTTPException` (503 AI_DB_NOT_CONFIGURED) được ném tiếp.
    Ném AiError `AI_QUESTION_REFUSED` (bộ bảo vệ từ chối) hoặc `AI_UNAVAILABLE` (không có relation, không đọc được schema, lỗi khác)."""
    engine = engine if engine is not None else engine_for(profile)
    token = _actor.set(actor)
    try:
        result = engine.analyze_data(question, scope)
    finally:
        _actor.reset(token)
    return outcome(result)


def outcome(result) -> dict:
    """Chuyển kết quả của engine thành dict thành công hoặc ném `AiError` tương ứng (lỗi hệ thống thành `AI_UNAVAILABLE`, còn lại là từ chối câu hỏi)."""
    if not isinstance(result, dict):
        raise ai_error(AI_UNAVAILABLE)
    error = result.get("error")
    if not error:
        return result
    if error in SYSTEM_FAILURES:
        log.warning("Analysis not available: %s", error)
        raise ai_error(AI_UNAVAILABLE)
    # mọi nội dung lỗi khác là việc từ chối câu truy vấn đã kiểm tra (sql_guard); thông điệp đó không hiển thị cho người gọi
    raise ai_error(AI_QUESTION_REFUSED)
