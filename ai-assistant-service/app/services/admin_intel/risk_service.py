"""Orchestrates one risk assessment: read the event, run layer A and B, optionally C, combine, store.

Layers A and B are cheap and run every time a score is built; layer C (the model) runs only when asked for and is never
needed to get a score. A stored assessment with the same content hash and rule version is returned as is.
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
    return signals.SignalSource(
        user=lambda user_id: upstream.user(authorization, user_id),
        approvals=lambda: upstream.approvals(authorization),
        category_prices=upstream.category_prices,
    )


def find_event(authorization: str, event_id: str) -> Optional[dict]:
    """The event as the event service reports it, or None when it cannot be read or does not exist."""
    try:
        for event in upstream.approvals(authorization):
            if str(event.get("id")) == str(event_id):
                return event
    except Exception as failure:
        log.warning("Event %s could not be read: %s", event_id, type(failure).__name__)
    return None


def _compute(event: dict, built: dict, authorization: str, ai_state: str, ai_flags: list) -> dict:
    ruleset = rules.load_rules()
    findings = rules.evaluate(built["texts"], ruleset) if ruleset else None
    results = signals.evaluate(event, built["texts"], _source(authorization))
    return engine.combine(findings, results, ai_state, ai_flags, ruleset.version if ruleset else None)


def _run_ai(built: dict, actor: str, invoke: Callable, check_limit: Optional[Callable]) -> tuple:
    """(state, valid_flags). A rate-limit refusal propagates (the caller gets 429); every other failure is 'failed'."""
    if check_limit:
        check_limit()
    try:
        raw = semantic.run(invoke, built["texts"], built["organizer_name"], actor)
        return "ran", engine.validate_ai_flags(raw, built["texts"])
    except AiError as failure:
        log.warning("Risk AI layer failed: %s", failure.code)
        return "failed", []
    except Exception as failure:
        log.warning("Risk AI layer failed: %s", type(failure).__name__)
        return "failed", []


def _finish(result: dict, event_id: str, built: dict, trigger: str, created_by: str, cached: bool = False) -> dict:
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

    ai_state, flags = "skipped", []
    if run_ai:
        ai_state, flags = _run_ai(built, actor, invoke or ai_invoke, check_limit)
    result = _compute(event, built, authorization, ai_state, flags)
    if not from_event:  # the content came from the client: say so, it is not a verified reading of the event
        result["reason_items"].append({"layer": "SIGNAL", "code": "UNAVAILABLE:event_fetch", "severity": "INFO", "weight": 0,
                                       "status": "not_assessed", "evidence": [],
                                       "message": "Không đọc được sự kiện từ máy chủ; nội dung do trình duyệt gửi lên."})
        result["coverage"]["signals"]["missing"].append("UNAVAILABLE:event_fetch")
        result["complete"] = False
    return _finish(result, event_id, built, trigger, actor)


def ensure_assessments(authorization: str, actor: str, events: list) -> dict:
    """Layer A+B assessments (no model call) for up to MAX_QUEUE_EVENTS events; stored ones are reused when the content is
    unchanged. Returns {event_id: assessment}. Events beyond the cap are simply absent."""
    out = {}
    for event in events[:MAX_QUEUE_EVENTS]:
        event_id = str(event.get("id"))
        try:
            out[event_id] = assess(authorization, actor, event_id, run_ai=False, trigger="queue", event=event)
        except Exception as failure:
            log.warning("Queue assessment of %s failed: %s", event_id, type(failure).__name__)
    return out


def ai_limit_checker(actor: str) -> Callable:
    return lambda: ratelimit.check("risk_scoring", actor, True)
