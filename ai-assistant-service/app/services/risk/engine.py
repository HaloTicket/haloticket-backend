"""Combines layers A (rules), B (signals) and C (model flags) into one assessment. Pure: no I/O, no model.

The score is computed here, never by the model: the model only supplies flags {code, quote, confidence}, each flag is
validated against the event text, and its points come from a fixed table. Layer C can only ADD points.
"""
import hashlib
import json
from typing import Optional

from app.core.config import settings

AI_FLAG_POINTS = {"CODED_CONTACT": 15, "VEILED_PROHIBITED": 25, "OVERPROMISE": 10, "SCAM_PATTERN": 20}
AI_FLAG_MESSAGES = {
    "CODED_CONTACT": "Có cách ghi thông tin liên lạc cá nhân được che chữ để tránh bị phát hiện.",
    "VEILED_PROHIBITED": "Có dấu hiệu quảng bá hàng hóa, dịch vụ bị cấm bằng cách nói vòng.",
    "OVERPROMISE": "Có lời hứa phóng đại hoặc cam kết không thực tế.",
    "SCAM_PATTERN": "Nội dung có dấu hiệu lừa đảo (thu tiền trước ngoài hệ thống, hứa hẹn bất thường).",
}
CONFIDENCE_FACTOR = {"HIGH": 1.0, "MEDIUM": 0.7, "LOW": 0.4}
LEVEL_ORDER = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}


def content_hash(parts: dict) -> str:
    """SHA-256 of the canonical JSON of the fields that decide the assessment."""
    canonical = json.dumps(parts, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def level_for(score: int) -> str:
    if score >= settings.risk_high_threshold:
        return "HIGH"
    if score >= settings.risk_medium_threshold:
        return "MEDIUM"
    return "LOW"


def validate_ai_flags(raw_flags, texts: dict) -> list:
    """Keeps only well-formed flags whose quote is a verbatim substring of one of the event texts."""
    kept, seen = [], set()
    if not isinstance(raw_flags, list):
        return kept
    for flag in raw_flags[:10]:
        if not isinstance(flag, dict):
            continue
        code = flag.get("code")
        quote = flag.get("quote")
        confidence = str(flag.get("confidence", "")).upper()
        if code not in AI_FLAG_POINTS or confidence not in CONFIDENCE_FACTOR or not isinstance(quote, str):
            continue
        quote = quote.strip()
        if not 3 <= len(quote) <= 200 or code in seen:
            continue
        field = next((name for name, text in texts.items() if text and quote in text), None)
        if field is None:
            continue  # the model quoted something the event does not say
        start = texts[field].index(quote)
        seen.add(code)
        kept.append({"code": code, "confidence": confidence, "evidence": {"field": field, "start": start, "end": start + len(quote), "text": quote}})
    return kept


def ai_findings(valid_flags: list) -> list:
    findings = []
    budget = settings.risk_ai_max_points
    for flag in valid_flags:
        points = min(budget, round(AI_FLAG_POINTS[flag["code"]] * CONFIDENCE_FACTOR[flag["confidence"]]))
        if points <= 0:
            continue
        budget -= points
        findings.append({
            "layer": "AI", "code": flag["code"], "severity": "HIGH" if points >= 15 else "MEDIUM", "weight": points,
            "message": AI_FLAG_MESSAGES[flag["code"]], "evidence": [flag["evidence"]], "confidence": flag["confidence"],
        })
    return findings


def combine(rule_findings: Optional[list], signal_results: list, ai_state: str, ai_flags: list,
            ruleset_version: Optional[str]) -> dict:
    """rule_findings None = layer A unavailable. signal_results: [{name, status, finding?}]. ai_state: ran|failed|skipped."""
    findings = list(rule_findings or [])
    missing = []
    assessed = 0
    for result in signal_results:
        if result["status"] == "assessed":
            assessed += 1
            if result.get("finding"):
                findings.append(result["finding"])
        else:
            missing.append(result["name"])
    base = sum(f["weight"] for f in findings)
    ai_items = ai_findings(ai_flags) if ai_state == "ran" else []
    findings.extend(ai_items)
    score = base + sum(f["weight"] for f in ai_items)
    if any(f.get("severity") == "BLOCK" for f in findings if f["layer"] == "RULE"):
        score = max(score, settings.risk_block_floor)
    score = max(0, min(100, int(score)))

    reasons = list(findings)
    for name in missing:
        reasons.append({"layer": "SIGNAL", "code": "UNAVAILABLE:" + name, "severity": "INFO", "weight": 0,
                        "status": "not_assessed", "message": "Không đánh giá được tín hiệu này (không lấy được dữ liệu).", "evidence": []})
    if rule_findings is None:
        reasons.append({"layer": "RULE", "code": "RULES_UNAVAILABLE", "severity": "INFO", "weight": 0,
                        "status": "not_assessed", "message": "Không nạp được bộ luật kiểm duyệt.", "evidence": []})
    complete = rule_findings is not None and not missing and ai_state == "ran"
    reasons.sort(key=lambda r: (-r["weight"], r["code"]))
    return {
        "risk_score": score,
        "risk_level": level_for(score),
        "reason_items": reasons,
        "reasons": [r["message"] for r in reasons if r.get("status") != "not_assessed"],
        "ruleset_version": ruleset_version,
        "coverage": {"rules": rule_findings is not None,
                     "signals": {"assessed": assessed, "total": len(signal_results), "missing": ["UNAVAILABLE:" + m for m in missing]},
                     "ai": ai_state},
        "complete": complete,
    }
