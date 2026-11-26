"""Đánh giá nội dung thông báo nội bộ bằng bộ luật kiểm duyệt hiện có."""
from hmac import compare_digest
from typing import Annotated

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, StringConstraints

from app.core.config import settings
from app.services.risk.engine import combine
from app.services.risk.rules import evaluate, load_rules

router = APIRouter(include_in_schema=False)


class NotificationContent(BaseModel):
    title: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=150)]
    message: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=5000)]


@router.post("/internal/notification-content-scan")
def scan_notification(
    body: NotificationContent,
    internal_token: Annotated[str, Header(alias="X-Internal-Token")] = "",
):
    configured = settings.authz_event_scope_internal_token
    if not configured.strip():
        raise HTTPException(status_code=503, detail="INTERNAL_SCAN_NOT_CONFIGURED")
    if not compare_digest(configured.encode(), internal_token.encode()):
        raise HTTPException(status_code=403, detail="FORBIDDEN")

    ruleset = load_rules()
    if ruleset is None:
        return {"status": "UNAVAILABLE"}

    findings = evaluate({"title": body.title, "description": body.message}, ruleset)
    result = combine(findings, [], "skipped", [], ruleset.version)
    return {
        "status": "COMPLETE",
        "level": result["risk_level"],
        "score": result["risk_score"],
        "findings": result["reasons"],
    }
