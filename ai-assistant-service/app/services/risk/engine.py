"""Combines layers A (rules), B (signals) and C (model flags) into one assessment. Pure: no I/O, no model.

The score is computed here, never by the model: the model only supplies flags {code, quote, confidence}, each flag is
validated against the event text, and its points come from a fixed table. Layer C can only ADD points.
"""
import hashlib
import json
from typing import Optional

from app.core.config import settings

AI_FLAG_POINTS = {"VEILED_PROHIBITED": 25, "OVERPROMISE": 10, "SCAM_PATTERN": 20, "SOCIAL_CONTACT": 15}
AI_FLAG_MESSAGES = {
    "VEILED_PROHIBITED": "Có dấu hiệu quảng bá hàng hóa, dịch vụ bị cấm bằng cách nói vòng.",
    "OVERPROMISE": "Có lời hứa phóng đại hoặc cam kết không thực tế.",
    "SOCIAL_CONTACT": "Dẫn khách liên hệ hoặc mua vé qua mạng xã hội / ứng dụng chat (kể cả viết che chữ) thay vì qua HaloTicket.",
    "SCAM_PATTERN": "Nội dung có dấu hiệu lừa đảo (thu tiền trước ngoài hệ thống, hứa hẹn bất thường).",
}
CONFIDENCE_FACTOR = {"HIGH": 1.0, "MEDIUM": 0.7, "LOW": 0.4}
# the organiser signals move together (a new account is usually also unverified), so they share one capped budget
ORGANIZER_SIGNAL_CODES = ("organizer_account_new", "organizer_account_young", "organizer_ekyc_not_approved", "organizer_history_rejected")
# a model flag that points at the same words as the rule that already scored them adds nothing
AI_RULE_EQUIVALENT = {
    "SOCIAL_CONTACT": ("contact_social",),
    "VEILED_PROHIBITED": ("prohibited_goods", "prohibited_ads"),
    "SCAM_PATTERN": ("profit_guarantee",),
    "OVERPROMISE": ("overpromise", "profit_guarantee"),  # a guaranteed profit is a scam pattern by definition, not an exaggeration
}
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


def _overlaps(a: dict, b: dict) -> bool:
    if a.get("field") != b.get("field"):
        return False
    try:
        return a["start"] < b["end"] and b["start"] < a["end"]
    except (KeyError, TypeError):
        return False


def drop_flags_already_scored(valid_flags: list, rule_findings: list) -> tuple:
    """(kept, dropped_count). A flag is dropped when its quote overlaps the evidence of the rule that covers the same kind of
    violation: the rule already scored those words, counting them again would inflate the score."""
    kept, dropped = [], 0
    for flag in valid_flags:
        covered = AI_RULE_EQUIVALENT.get(flag["code"], ())
        duplicate = any(f.get("code") in covered and f.get("weight", 0) > 0 and any(_overlaps(flag["evidence"], e) for e in f.get("evidence", []))
                        for f in rule_findings)
        if duplicate:
            dropped += 1
        else:
            kept.append(flag)
    return kept, dropped


def cap_organizer_signals(findings: list) -> list:
    """The organiser signals share settings.risk_organizer_signal_cap points; the largest are kept whole, the one that crosses
    the cap is cut, the rest score 0. They stay in the reasons (capped=True) so the reviewer still sees them."""
    cap = settings.risk_organizer_signal_cap
    group = sorted((f for f in findings if f.get("code") in ORGANIZER_SIGNAL_CODES), key=lambda f: -f["weight"])
    if sum(f["weight"] for f in group) <= cap:
        return findings
    left, replaced = cap, {}
    for f in group:
        weight = min(f["weight"], left)
        left -= weight
        if weight != f["weight"]:
            replaced[id(f)] = dict(f, weight=weight, capped=True)
    return [replaced.get(id(f), f) for f in findings]


def policy_floor(findings: list) -> tuple:
    """(level, codes): the lowest level the findings force, from the policy in LABELING_GUIDE.md. A finding that names a violation
    carries its own minimum (min_level); two profile signals that are weak alone force MEDIUM together (a new account that is
    also not verified; an account under 30 days that is not verified); a content violation together with any profile signal is HIGH. The additive score still orders the queue and adds
    up weak evidence; the floor only keeps a clear violation from being filed as LOW because its weight was small."""
    level, because = "LOW", []
    for f in findings:
        floor = f.get("min_level", "LOW")
        if f.get("weight", 0) > 0 and LEVEL_ORDER.get(floor, 0) > 0:
            because.append(f["code"])
            if LEVEL_ORDER[floor] > LEVEL_ORDER[level]:
                level = floor
    codes = {f["code"] for f in findings if f.get("weight", 0) > 0}
    if "organizer_ekyc_not_approved" in codes and codes & {"organizer_account_new", "organizer_account_young"}:
        because.append("organizer_account+ekyc")
        level = level if LEVEL_ORDER[level] >= 1 else "MEDIUM"
    # independent indicators together: a content violation (rule, or a checked model flag) and a weak profile signal on the
    # same event is HIGH ("many suspicious signs at once", guide section 3), though each alone is MEDIUM at most
    content = any(f.get("layer") in ("RULE", "AI") and f.get("weight", 0) > 0 for f in findings)
    if content and codes & set(ORGANIZER_SIGNAL_CODES):
        because.append("content+organizer")
        level = "HIGH"
    return level, because


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
            # a checked flag is a violation signal: at least MEDIUM. HIGH only for an explicit one (high confidence, veiled prohibited
            # goods) that also passed the second reading; any other flag alone stays at MEDIUM, like the 30-point cap
            "min_level": "HIGH" if flag.get("verified") and flag["confidence"] == "HIGH" and flag["code"] == "VEILED_PROHIBITED" else "MEDIUM",
        })
    return findings


def combine(rule_findings: Optional[list], signal_results: list, ai_state: str, ai_flags: list,
            ruleset_version: Optional[str], ai_meta: Optional[dict] = None) -> dict:
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
    findings = cap_organizer_signals(findings)
    base = sum(f["weight"] for f in findings)
    overlap_dropped = 0
    if ai_state == "ran":
        ai_flags, overlap_dropped = drop_flags_already_scored(ai_flags, rule_findings or [])
    ai_items = ai_findings(ai_flags) if ai_state == "ran" else []
    findings.extend(ai_items)
    score = base + sum(f["weight"] for f in ai_items)
    if any(f.get("severity") == "BLOCK" for f in findings if f["layer"] == "RULE"):
        score = max(score, settings.risk_block_floor)
    floor, floor_codes = policy_floor(findings)
    if floor != "LOW":
        score = max(score, settings.risk_high_threshold if floor == "HIGH" else settings.risk_medium_threshold)
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
        "reasons": [r["message"] for r in reasons if r.get("status") != "not_assessed" and r["weight"] > 0],
        "ruleset_version": ruleset_version,
        "coverage": {"rules": rule_findings is not None,
                     "signals": {"assessed": assessed, "total": len(signal_results), "missing": ["UNAVAILABLE:" + m for m in missing]},
                     "ai": ai_state, "ai_overlap_dropped": overlap_dropped, **({"ai_meta": ai_meta} if ai_meta else {}),
                     **({"policy_floor": {"level": floor, "because": floor_codes}} if floor != "LOW" else {})},
        "complete": complete,
    }
