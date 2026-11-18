"""Kết hợp lớp A (luật), B (tín hiệu) và C (cờ của mô hình) thành một bản đánh giá. Thuần túy: không I/O, không gọi mô hình.

Điểm số được tính ở đây, không bao giờ do mô hình tính: mô hình chỉ đưa ra các cờ {code, quote, confidence}, mỗi cờ được
đối chiếu với văn bản sự kiện, và điểm của nó lấy từ một bảng cố định. Lớp C chỉ có thể CỘNG thêm điểm.
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
# các tín hiệu về ban tổ chức thường đi cùng nhau (tài khoản mới thường cũng chưa xác minh), nên dùng chung một ngân sách điểm có trần
ORGANIZER_SIGNAL_CODES = ("organizer_account_new", "organizer_account_young", "organizer_ekyc_not_approved", "organizer_history_rejected")
# một cờ của mô hình trỏ vào đúng những từ mà luật đã chấm điểm thì không cộng thêm gì
AI_RULE_EQUIVALENT = {
    "SOCIAL_CONTACT": ("contact_social",),
    "VEILED_PROHIBITED": ("prohibited_goods", "prohibited_ads"),
    "SCAM_PATTERN": ("profit_guarantee",),
    "OVERPROMISE": ("overpromise", "profit_guarantee"),  # lợi nhuận được đảm bảo là dấu hiệu lừa đảo theo định nghĩa, không phải sự phóng đại
}
LEVEL_ORDER = {"LOW": 0, "MEDIUM": 1, "HIGH": 2}


def content_hash(parts: dict) -> str:
    """SHA-256 của JSON chuẩn tắc của các trường quyết định kết quả đánh giá."""
    canonical = json.dumps(parts, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def level_for(score: int) -> str:
    """Đổi điểm số sang mức rủi ro: HIGH, MEDIUM hoặc LOW theo ngưỡng cấu hình."""
    if score >= settings.risk_high_threshold:
        return "HIGH"
    if score >= settings.risk_medium_threshold:
        return "MEDIUM"
    return "LOW"


def validate_ai_flags(raw_flags, texts: dict) -> list:
    """Chỉ giữ các cờ đúng định dạng và có trích dẫn là chuỗi con nguyên văn của một trong các văn bản sự kiện (tối đa 10 cờ, mỗi mã một cờ)."""
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
            continue  # mô hình trích dẫn thứ mà sự kiện không hề viết
        start = texts[field].index(quote)
        seen.add(code)
        kept.append({"code": code, "confidence": confidence, "evidence": {"field": field, "start": start, "end": start + len(quote), "text": quote}})
    return kept


def _overlaps(a: dict, b: dict) -> bool:
    """Hai bằng chứng có chồng lấn không: cùng trường và hai khoảng ký tự giao nhau."""
    if a.get("field") != b.get("field"):
        return False
    try:
        return a["start"] < b["end"] and b["start"] < a["end"]
    except (KeyError, TypeError):
        return False


def drop_flags_already_scored(valid_flags: list, rule_findings: list) -> tuple:
    """Trả (kept, dropped_count). Một cờ bị bỏ khi trích dẫn của nó chồng lên bằng chứng của luật phủ cùng loại vi phạm:
    luật đã chấm điểm những từ đó, tính lại sẽ làm điểm bị thổi phồng."""
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
    """Các tín hiệu về ban tổ chức dùng chung settings.risk_organizer_signal_cap điểm; tín hiệu lớn nhất được giữ nguyên, tín hiệu làm vượt
    trần bị cắt bớt, phần còn lại tính 0 điểm. Chúng vẫn nằm trong lý do (capped=True) để người duyệt vẫn thấy."""
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
    """Trả (level, codes): mức thấp nhất mà các phát hiện buộc phải có, theo chính sách trong LABELING_GUIDE.md. Phát hiện nêu một vi phạm
    mang mức tối thiểu riêng (min_level); hai tín hiệu hồ sơ vốn yếu khi đứng riêng thì cùng nhau buộc MEDIUM (tài khoản mới mà
    chưa xác minh; tài khoản dưới 30 ngày mà chưa xác minh); vi phạm nội dung cùng với bất kỳ tín hiệu hồ sơ nào là HIGH. Điểm cộng dồn vẫn dùng để
    xếp hàng đợi và cộng các bằng chứng yếu; mức sàn chỉ ngăn một vi phạm rõ ràng bị xếp LOW chỉ vì trọng số nhỏ."""
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
    # các chỉ báo độc lập đi cùng nhau: vi phạm nội dung (luật, hoặc cờ mô hình đã kiểm tra) và một tín hiệu hồ sơ yếu trên cùng
    # sự kiện là HIGH ("nhiều dấu hiệu đáng ngờ cùng lúc", mục 3 của hướng dẫn), dù mỗi cái đứng riêng chỉ tối đa MEDIUM
    content = any(f.get("layer") in ("RULE", "AI") and f.get("weight", 0) > 0 for f in findings)
    if content and codes & set(ORGANIZER_SIGNAL_CODES):
        because.append("content+organizer")
        level = "HIGH"
    return level, because


def ai_findings(valid_flags: list) -> list:
    """Đổi các cờ hợp lệ của mô hình thành phát hiện có điểm = điểm gốc x hệ số tin cậy, trong ngân sách settings.risk_ai_max_points."""
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
            # cờ đã kiểm tra là tín hiệu vi phạm: tối thiểu MEDIUM. HIGH chỉ cho cờ tường minh (độ tin cậy cao, hàng cấm nói vòng)
            # đã qua lần đọc thứ hai; mọi cờ khác đứng riêng vẫn ở MEDIUM, như trần 30 điểm
            "min_level": "HIGH" if flag.get("verified") and flag["confidence"] == "HIGH" and flag["code"] == "VEILED_PROHIBITED" else "MEDIUM",
        })
    return findings


def combine(rule_findings: Optional[list], signal_results: list, ai_state: str, ai_flags: list,
            ruleset_version: Optional[str], ai_meta: Optional[dict] = None) -> dict:
    """Tổng hợp điểm và lý do. rule_findings None = lớp A không khả dụng. signal_results: [{name, status, finding?}]. ai_state: ran|failed|skipped.
    Điểm = tổng trọng số (có trần cho tín hiệu ban tổ chức, bỏ cờ AI trùng luật), nâng lên sàn của chính sách rồi kẹp trong 0..100."""
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
