"""Điều phối một lần đánh giá rủi ro: đọc sự kiện, chạy lớp A và B, tùy chọn lớp C, kết hợp rồi lưu.

Lớp A và B rẻ và chạy mỗi lần dựng điểm; lớp C (mô hình) chỉ chạy khi được yêu cầu và không bao giờ
là điều kiện để có điểm. Bản đánh giá đã lưu có cùng mã băm nội dung và phiên bản luật được trả về nguyên trạng.
"""
import logging
from datetime import datetime, timezone
from typing import Callable, Optional

from app.core import ratelimit
from app.core.errors import AiError
from app.services.ai.ai_call import ai_invoke
from app.services.admin_intel import store, upstream
from app.services.risk import content as risk_content
from app.services.risk import engine, rules, semantic, signals

log = logging.getLogger("ai.admin.risk")

MAX_QUEUE_EVENTS = 30


def _source(authorization: str) -> signals.SignalSource:
    """Dựng nguồn dữ liệu cho các tín hiệu (người dùng, danh sách duyệt, giá theo danh mục) dùng token của admin."""
    return signals.SignalSource(
        user=lambda user_id: upstream.user(authorization, user_id),
        approvals=lambda: upstream.approvals(authorization),
        category_prices=upstream.category_prices,
    )


def find_event(authorization: str, event_id: str) -> Optional[dict]:
    """Sự kiện theo báo cáo của event service, hoặc `None` khi không đọc được hoặc không tồn tại."""
    try:
        for event in upstream.approvals(authorization):
            if str(event.get("id")) == str(event_id):
                return event
    except Exception as failure:
        log.warning("Event %s could not be read: %s", event_id, type(failure).__name__)
    return None


def _compute(event: dict, built: dict, authorization: str, ai_state: str, ai_flags: list, ai_meta: Optional[dict] = None) -> dict:
    """Chạy luật (lớp A) và tín hiệu (lớp B) rồi kết hợp với kết quả lớp AI thành một điểm rủi ro."""
    ruleset = rules.load_rules()
    findings = rules.evaluate(built["texts"], ruleset) if ruleset else None
    results = signals.evaluate(event, built["texts"], _source(authorization))
    return engine.combine(findings, results, ai_state, ai_flags, ruleset.version if ruleset else None, ai_meta)


def _run_ai(built: dict, actor: str, invoke: Callable, check_limit: Optional[Callable]) -> tuple:
    """Trả `(state, valid_flags, meta)`. Lỗi bị giới hạn tần suất được ném tiếp (client nhận 429); mọi lỗi khác thành 'failed'.
    Cờ qua bước kiểm tra trích dẫn được đọc lần hai (`semantic.verify`), lần đọc này chỉ có thể loại bớt cờ. Nếu chính lần đọc đó
    lỗi thì cờ được giữ lại và `meta` ghi rõ: mất một phát hiện thật vì sự cố còn tệ hơn."""
    if check_limit:
        check_limit()
    try:
        raw, meta = semantic.run_with_meta(invoke, built["texts"], built["organizer_name"], actor)
        flags = engine.validate_ai_flags(raw, built["texts"])
        meta["flags_proposed"] = len(raw)
        meta["verify"] = "skipped"
        if flags:
            try:
                confirmed = semantic.verify(invoke, built["texts"], flags, actor)
                meta["verify"] = "ran"
                meta["verify_removed"] = [f["code"] for f in flags if f["code"] not in confirmed]
                # cờ qua lần đọc thứ hai được đánh dấu: chỉ cờ như vậy mới được mang mức HIGH tối thiểu (engine.ai_findings)
                flags = [{**f, "verified": True} for f in flags if f["code"] in confirmed]
            except Exception as failure:
                meta["verify"] = "failed"
                log.warning("Risk flag verification failed: %s", getattr(failure, "code", type(failure).__name__))
        return "ran", flags, meta
    except AiError as failure:
        log.warning("Risk AI layer failed: %s", failure.code)
        return "failed", [], {}
    except Exception as failure:
        log.warning("Risk AI layer failed: %s", type(failure).__name__)
        return "failed", [], {}


def _finish(result: dict, event_id: str, built: dict, trigger: str, created_by: str, cached: bool = False) -> dict:
    """Bổ sung mã sự kiện, mã băm nội dung, thời điểm đánh giá và lưu kết quả (trừ khi lấy từ bộ nhớ đã lưu); lỗi lưu chỉ ghi log."""
    result = dict(result)
    result.update(event_id=event_id, content_hash=built["hash"], cached=cached, stored=False,
                  assessed_at=result.get("assessed_at") or datetime.now(timezone.utc).isoformat())
    if not cached and store.configured():
        try:
            result["assessment_id"] = store.save_assessment(event_id, result, built["hash"], trigger, created_by)
            result["stored"] = True
        except Exception as failure:
            log.warning("Assessment of %s not stored: %s", event_id, type(failure).__name__)
    elif cached:
        result["stored"] = True
    result["ai_layer"] = result["coverage"]["ai"]
    return result


def _from_stored(stored: dict) -> dict:
    """Chuyển bản ghi đánh giá đã lưu về cùng dạng kết quả như khi vừa tính."""
    items = stored["reason_items"]
    return {
        "risk_score": stored["risk_score"], "risk_level": stored["risk_level"], "reason_items": items,
        "reasons": [r.get("message", "") for r in items if r.get("status") != "not_assessed"],
        "ruleset_version": stored["ruleset_version"], "coverage": stored["coverage"], "complete": stored["complete"],
        "assessed_at": stored["assessed_at"], "assessment_id": stored["assessment_id"],
    }


def assess(authorization: str, actor: str, event_id: str, *, fallback: Optional[dict] = None, refresh: bool = False,
           run_ai: bool = True, trigger: str = "auto", invoke: Optional[Callable] = None,
           check_limit: Optional[Callable] = None, event: Optional[dict] = None) -> dict:
    """Đánh giá rủi ro một sự kiện.

    Đọc sự kiện từ event service (không đọc được thì dùng `fallback` từ client và đánh dấu chưa đầy đủ). Trừ khi `refresh`, nếu đã có bản
    lưu cùng mã băm nội dung và phiên bản luật (và đã chạy lớp AI khi cần) thì trả bản đó. Lớp AI chỉ chạy khi `run_ai` là True.
    """
    event = event or find_event(authorization, event_id)
    from_event = event is not None
    if event is None:
        fb = fallback or {}
        event = {"id": event_id, "title": fb.get("title", ""), "description": fb.get("description", ""), "organizerName": fb.get("organizer_name", "")}
    built = risk_content.build(event)
    ruleset = rules.load_rules()
    version = ruleset.version if ruleset else None

    if from_event and not refresh and store.configured():
        try:
            latest = store.latest_assessment(event_id)
        except Exception as failure:
            log.warning("Stored assessment of %s unreadable: %s", event_id, type(failure).__name__)
            latest = None
        if latest and latest["content_hash"] == built["hash"] and latest["ruleset_version"] == (version or "") \
                and (latest["ai_layer"] == "ran" or not run_ai):
            return _finish(_from_stored(latest), event_id, built, trigger, actor, cached=True)

    ai_state, flags, ai_meta = "skipped", [], {}
    if run_ai:
        ai_state, flags, ai_meta = _run_ai(built, actor, invoke or ai_invoke, check_limit)
    result = _compute(event, built, authorization, ai_state, flags, ai_meta)
    if not from_event:  # nội dung do client gửi: ghi rõ, đây không phải bản đọc đã xác minh của sự kiện
        result["reason_items"].append({"layer": "SIGNAL", "code": "UNAVAILABLE:event_fetch", "severity": "INFO", "weight": 0,
                                       "status": "not_assessed", "evidence": [],
                                       "message": "Không đọc được sự kiện từ máy chủ; nội dung do trình duyệt gửi lên."})
        result["coverage"]["signals"]["missing"].append("UNAVAILABLE:event_fetch")
        result["complete"] = False
    return _finish(result, event_id, built, trigger, actor)


def ensure_assessments(authorization: str, actor: str, events: list) -> dict:
    """Đánh giá lớp A+B (không gọi mô hình) cho tối đa `MAX_QUEUE_EVENTS` sự kiện; bản đã lưu được dùng lại khi nội dung không đổi.
    Trả `{event_id: đánh giá}`. Sự kiện vượt giới hạn hoặc bị lỗi sẽ không có trong kết quả."""
    out = {}
    for event in events[:MAX_QUEUE_EVENTS]:
        event_id = str(event.get("id"))
        try:
            out[event_id] = assess(authorization, actor, event_id, run_ai=False, trigger="queue", event=event)
        except Exception as failure:
            log.warning("Queue assessment of %s failed: %s", event_id, type(failure).__name__)
    return out


def ai_limit_checker(actor: str) -> Callable:
    """Tạo hàm kiểm tra giới hạn tần suất chấm điểm rủi ro cho `actor`, ném `AI_RATE_LIMITED` khi vượt."""
    return lambda: ratelimit.check("risk_scoring", actor, True)
