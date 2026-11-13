"""What an annotator sees for a case: the event and the facts a reviewer would have. Never the score, the group, the
generator's intention or any system output."""
import statistics
from datetime import datetime

from app.services.risk import signals
from research.risk.common import FIXED_NOW


def _days_ago(iso: str) -> int:
    parsed = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    return max(0, (FIXED_NOW - parsed).days)


def organiser_facts(case: dict) -> dict:
    uid = (case["event"].get("organizer") or {}).get("userId")
    others = [e for e in case["fixtures"]["approvals"]
              if isinstance(e.get("organizer"), dict) and e["organizer"].get("userId") == uid]
    user = case["fixtures"]["user"]
    return {
        "age_days": _days_ago(user["createdAt"]), "ekyc": user.get("ekycStatus"),
        "approved": sum(1 for e in others if e.get("eventApprovalStatus") == "APPROVED"),
        "rejected": sum(1 for e in others if e.get("eventApprovalStatus") == "REJECTED"),
    }


def most_similar(case: dict) -> tuple:
    """(event, similarity) of the existing event whose title and text are closest, or (None, 0.0)."""
    event = case["event"]
    mine = f"{event.get('title', '')} {event.get('description', '')}"
    best, best_score = None, 0.0
    for other in case["fixtures"]["approvals"]:
        score = signals.similarity(mine, f"{other.get('title', '')} {other.get('description', '')}")
        if score > best_score:
            best, best_score = other, score
    return best, best_score


def render(case: dict, full_similar: bool = False) -> str:
    """full_similar: print the whole description of the most similar event instead of its first 220 characters
    (used when adjudicating, so a DUPLICATE decision can check that the text is really contained)."""
    event = case["event"]
    prices = [p for p in case["fixtures"]["category_prices"] if p]
    facts = organiser_facts(case)
    lines = [
        f"Tiêu đề      : {event.get('title')}",
        f"Thể loại     : {event.get('categoryName')}",
        f"Ban tổ chức  : {(event.get('organizer') or {}).get('name')}",
        "Hạng vé      : " + "; ".join(f"{t['name']} {int(t['price']):,} đ".replace(",", ".") for t in event.get("tickets", [])),
    ]
    if len(prices) >= 5:
        lines.append(f"Giá trung vị thể loại: {int(statistics.median(prices)):,} đ ({len(prices)} sự kiện so sánh)".replace(",", "."))
    else:
        lines.append(f"Giá trung vị thể loại: chưa đủ dữ liệu so sánh (chỉ {len(prices)} sự kiện)")
    place = ", ".join(x for x in (event.get("locationName"), event.get("address")) if x) or "(không có địa điểm)"
    lines += [f"Địa điểm     : {place}", f"Thời gian    : {event.get('startTime')}  đến  {event.get('endTime')}",
              f"Tính đến ngày: {FIXED_NOW.date()}", "",
              f"Hồ sơ ban tổ chức: tài khoản tạo {facts['age_days']} ngày trước; eKYC: {facts['ekyc']}; "
              f"đã duyệt {facts['approved']}, bị từ chối {facts['rejected']} sự kiện."]
    other, score = most_similar(case)
    if other is not None and score >= 0.3:
        same = (other.get("organizer") or {}).get("userId") == (event.get("organizer") or {}).get("userId")
        lines.append(f"Sự kiện giống nhất đã có ({'cùng ban tổ chức' if same else 'ban tổ chức khác'}, {other.get('eventApprovalStatus')}): "
                     f"{other.get('title')}\n    {str(other.get('description')) if full_similar else str(other.get('description'))[:220]}")
    else:
        lines.append("Sự kiện giống nhất đã có: không có sự kiện nào đáng kể.")
    lines += ["", "MÔ TẢ:", event.get("descriptionText") or event.get("description") or ""]
    return "\n".join(lines)
