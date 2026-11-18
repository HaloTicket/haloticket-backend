"""Những gì người gán nhãn thấy ở một ca: sự kiện và các dữ kiện mà người duyệt sẽ có. Không bao giờ có điểm, nhóm,
ý định của bộ sinh hay bất kỳ đầu ra nào của hệ thống.
"""
import statistics
from datetime import datetime

from app.services.risk import signals
from research.risk.common import FIXED_NOW


def _days_ago(iso: str) -> int:
    """Số ngày từ một mốc ISO tới thời điểm cố định FIXED_NOW (không âm)."""
    parsed = datetime.fromisoformat(iso.replace("Z", "+00:00"))
    return max(0, (FIXED_NOW - parsed).days)


def organiser_facts(case: dict) -> dict:
    """Dữ kiện về nhà tổ chức của ca: tuổi tài khoản (ngày), trạng thái eKYC, số sự kiện đã duyệt và bị từ chối của họ trong lịch sử."""
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
    """Tìm sự kiện đã có giống nhất (theo tiêu đề và mô tả).

    Returns:
        (sự kiện, độ giống), hoặc (None, 0.0) nếu không có.
    """
    event = case["event"]
    mine = f"{event.get('title', '')} {event.get('description', '')}"
    best, best_score = None, 0.0
    for other in case["fixtures"]["approvals"]:
        score = signals.similarity(mine, f"{other.get('title', '')} {other.get('description', '')}")
        if score > best_score:
            best, best_score = other, score
    return best, best_score


def render(case: dict, full_similar: bool = False) -> str:
    """Dựng văn bản một ca cho người gán nhãn: tiêu đề, thể loại, hạng vé, giá trung vị thể loại, địa điểm, thời gian, hồ sơ nhà tổ chức, sự kiện giống nhất và mô tả.

    Args:
        case: ca cần hiện.
        full_similar: True thì in toàn bộ mô tả của sự kiện giống nhất thay vì 220 ký tự đầu (dùng khi phân xử, để quyết định DUPLICATE kiểm tra được văn bản có thật sự nằm trong đó).
    """
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
