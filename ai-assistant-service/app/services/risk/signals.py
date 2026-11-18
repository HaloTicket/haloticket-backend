"""Lớp B: tín hiệu từ dữ liệu. Tín hiệu không đọc được là NOT_ASSESSED: không cộng điểm và không bao giờ được coi là an toàn.

Dữ liệu đi vào qua một SignalSource (các hàm), nên kiểm thử cấp dữ liệu giả còn production cấp bộ đọc HTTP và SQL
từ app.services.admin_intel.upstream. Bộ đọc nào cũng có thể ném ngoại lệ; ở đây không cho ngoại lệ thoát ra ngoài.
"""
import logging
import re
import statistics
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Callable, Optional

log = logging.getLogger("ai.risk.signals")

MIN_PRICE_SAMPLES = 5
DUPLICATE_THRESHOLD = 0.6
DUPLICATE_TITLE_THRESHOLD = 0.5  # "Giải cờ vua Đà Nẵng" so với "Giải cờ vua Cần Thơ" được 0.56: đổi thành phố vẫn tính là cùng tên
DUPLICATE_CERTAIN = 0.9  # riêng phần mô tả gần giống một sự kiện khác tới mức này thì là bản sao, bất kể tên
MAX_DUPLICATE_CANDIDATES = 500


@dataclass
class SignalSource:
    """Các hàm đọc dữ liệu cho lớp B: chi tiết người dùng, toàn bộ sự kiện và giá thấp nhất theo thể loại."""
    user: Callable[[str], dict]                 # chi tiết admin của user-service: createdAt, ekycStatus
    approvals: Callable[[], list]               # mọi sự kiện (dict có dạng EventResponse)
    category_prices: Callable[[str], list]      # min_price của các sự kiện APPROVED trong một thể loại (v_events)


def _finding(code: str, weight: int, message: str, severity: str = "MEDIUM", evidence: Optional[list] = None, min_level: str = "LOW") -> dict:
    """Dựng một phát hiện của lớp tín hiệu với các trường chuẩn (layer, code, severity, weight, message, evidence, min_level)."""
    return {"layer": "SIGNAL", "code": code, "severity": severity, "weight": weight, "message": message, "evidence": evidence or [],
            "min_level": min_level}


def title_similarity(a: str, b: str) -> float:
    """Jaccard theo từ của hai tên (đổi thành phố hay năm trong tên vốn giống nhau vẫn được điểm cao)."""
    words = lambda text: set(re.findall(r"\w+", (text or "").lower()))
    left, right = words(a), words(b)
    return len(left & right) / len(left | right) if left | right else 0.0


def _parse_time(value) -> Optional[datetime]:
    """Đọc thời gian ISO (chấp nhận hậu tố Z); thiếu múi giờ thì coi là UTC. Trả None nếu rỗng hoặc sai định dạng."""
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _shingles(text: str) -> set:
    """Tập các cụm 3 từ liên tiếp của văn bản (văn bản dưới 3 từ thì dùng cả câu)."""
    words = re.findall(r"\w+", (text or "").lower())
    if len(words) < 3:
        return {" ".join(words)} if words else set()
    return {" ".join(words[i:i + 3]) for i in range(len(words) - 2)}


def similarity(a: str, b: str) -> float:
    """Độ giống nhau của hai văn bản theo Jaccard trên các cụm 3 từ; 0.0 nếu một bên rỗng."""
    sa, sb = _shingles(a), _shingles(b)
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / len(sa | sb)


def _guard(name: str, function: Callable) -> dict:
    """Chạy một hàm đánh giá tín hiệu; lỗi thì ghi log và trả not_assessed thay vì cho ngoại lệ lan ra."""
    try:
        finding = function()
    except Exception as failure:
        log.warning("Signal %s not assessed: %s", name, type(failure).__name__)
        return {"name": name, "status": "not_assessed"}
    return {"name": name, "status": "assessed", "finding": finding}


def evaluate(event: dict, texts: dict, source: SignalSource, now: Optional[datetime] = None) -> list:
    """Sáu tín hiệu cho một sự kiện (dict có dạng EventResponse). Trả [{name, status, finding?}]."""
    now = now or datetime.now(timezone.utc)
    organizer = event.get("organizer") if isinstance(event.get("organizer"), dict) else {}
    user_id = organizer.get("userId")
    event_id = str(event.get("id") or "")
    cache: dict = {}

    def user() -> dict:
        """Chi tiết người dùng của ban tổ chức, gọi một lần rồi dùng lại; ném LookupError nếu không có id ban tổ chức."""
        if "user" not in cache:
            if not user_id:
                raise LookupError("no organizer id")
            cache["user"] = source.user(str(user_id))
        return cache["user"]

    def events() -> list:
        """Toàn bộ sự kiện, gọi một lần rồi dùng lại."""
        if "events" not in cache:
            cache["events"] = source.approvals()
        return cache["events"]

    def account():
        """Tín hiệu tuổi tài khoản: dưới 7 ngày (12 điểm, sàn MEDIUM) hoặc dưới 30 ngày (6 điểm); lâu hơn thì không có phát hiện."""
        created = _parse_time(user().get("createdAt"))
        if created is None:
            raise LookupError("no createdAt")
        age = now - created
        if age < timedelta(days=7):
            return _finding("organizer_account_new", 12, "Tài khoản ban tổ chức mới tạo chưa đầy 7 ngày.", "MEDIUM",
                            [{"field": "organizer", "text": f"tạo {age.days} ngày trước"}], "MEDIUM")
        if age < timedelta(days=30):
            return _finding("organizer_account_young", 6, "Tài khoản ban tổ chức tạo chưa đầy 30 ngày.", "MEDIUM",
                            [{"field": "organizer", "text": f"tạo {age.days} ngày trước"}])
        return None

    def ekyc():
        """Tín hiệu eKYC: ban tổ chức chưa ở trạng thái APPROVED thì 15 điểm."""
        status = user().get("ekycStatus")
        if status is None:
            raise LookupError("no ekyc status")
        if str(status).upper() != "APPROVED":
            return _finding("organizer_ekyc_not_approved", 15, "Ban tổ chức chưa được xác minh eKYC.", "HIGH",
                            [{"field": "organizer", "text": f"eKYC: {status}"}])
        return None

    def history():
        """Tín hiệu lịch sử: các sự kiện khác của ban tổ chức bị từ chối từ 2 lần trở lên và nhiều hơn số được duyệt thì 15 điểm."""
        if not user_id:
            raise LookupError("no organizer id")
        others = [e for e in events()
                  if isinstance(e.get("organizer"), dict) and e["organizer"].get("userId") == user_id and str(e.get("id")) != event_id]
        approved = sum(1 for e in others if e.get("eventApprovalStatus") == "APPROVED")
        rejected = sum(1 for e in others if e.get("eventApprovalStatus") == "REJECTED")
        if rejected >= 2 and rejected > approved:
            return _finding("organizer_history_rejected", 15, "Ban tổ chức từng bị từ chối nhiều sự kiện hơn số sự kiện được duyệt.", "HIGH",
                            [{"field": "organizer", "text": f"đã duyệt {approved}, từ chối {rejected}"}], "MEDIUM")
        return None

    def price():
        """Tín hiệu giá: giá vé thấp nhất lớn hơn 5 lần hoặc nhỏ hơn 0,1 lần trung vị của cùng thể loại thì 10 điểm; cần ít nhất MIN_PRICE_SAMPLES mẫu so sánh."""
        category = event.get("categoryName")
        price_now = event.get("minPrice")
        if not category or price_now is None:
            raise LookupError("no category or price")
        prices = [float(p) for p in source.category_prices(str(category)) if p is not None and float(p) > 0]
        if len(prices) < MIN_PRICE_SAMPLES:
            raise LookupError("too few comparable events")
        median = statistics.median(prices)
        price_now = float(price_now)
        if price_now > median * 5 or 0 < price_now < median * 0.1:
            return _finding("price_outlier", 10, "Giá vé thấp nhất lệch xa trung vị của cùng thể loại.", "MEDIUM",
                            [{"field": "price", "text": f"{int(price_now)} so với trung vị {int(median)}"}], "MEDIUM")
        return None

    def duplicate():
        """Tín hiệu trùng lặp: tìm sự kiện của người khác giống nhất về nội dung (và về tên, trừ khi mô tả gần như y hệt) thì 25 điểm."""
        mine = f"{texts.get('title', '')} {texts.get('description', '')}"
        best_score, best_event = 0.0, None
        for other in events()[:MAX_DUPLICATE_CANDIDATES]:
            if str(other.get("id")) == event_id:
                continue
            other_organizer = other.get("organizer") if isinstance(other.get("organizer"), dict) else {}
            if user_id and other_organizer.get("userId") == user_id and other.get("eventApprovalStatus") == "APPROVED":
                continue  # chương trình mà chính ban tổ chức này đã được duyệt (một series, một mùa mới) không phải bản sao của người khác
            other_text = f"{other.get('title', '')} {other.get('descriptionText') or other.get('description') or ''}"
            score = similarity(mine, other_text)
            # bản sao giữ nguyên tên (hoặc chỉ đổi thành phố hay năm): nội dung giống nhưng tên không liên quan thì chưa đủ
            same_description = similarity(texts.get("description", ""), str(other.get("descriptionText") or other.get("description") or ""))
            counts = same_description >= DUPLICATE_CERTAIN or (score >= DUPLICATE_THRESHOLD
                                                              and title_similarity(texts.get("title", ""), other.get("title", "")) >= DUPLICATE_TITLE_THRESHOLD)
            if counts and score > best_score:
                best_score, best_event = score, other
        if best_event is not None:
            return _finding("near_duplicate", 25, "Nội dung gần trùng với một sự kiện khác.", "HIGH",
                            [{"field": "description", "text": f"{int(best_score * 100)}% giống '{str(best_event.get('title'))[:80]}'"}], "MEDIUM")
        return None

    def schedule():
        """Tín hiệu lịch và địa điểm: thiếu hoặc sai thời gian, thiếu địa điểm (trừ sự kiện online); mỗi vấn đề 10 điểm, tối đa 20."""
        issues = []
        start, end = _parse_time(event.get("startTime")), _parse_time(event.get("endTime"))
        approval = str(event.get("eventApprovalStatus") or "")
        if not start:
            issues.append("thiếu thời gian bắt đầu")
        elif approval in ("PENDING", "UPDATE_PENDING") and start < now:
            issues.append("thời gian bắt đầu đã ở quá khứ")
        if start and end and end < start:
            issues.append("thời gian kết thúc trước thời gian bắt đầu")
        location_type = str(event.get("locationType") or "").upper()
        if "ONLINE" not in location_type and not event.get("locationName") and not event.get("address"):
            issues.append("thiếu địa điểm")
        if not issues:
            return None
        return _finding("schedule_anomaly", min(20, 10 * len(issues)), "Địa điểm hoặc thời gian bất thường: " + "; ".join(issues) + ".",
                        "MEDIUM", [{"field": "schedule", "text": "; ".join(issues)}], "MEDIUM")

    return [_guard("organizer_account", account), _guard("organizer_ekyc", ekyc), _guard("organizer_history", history),
            _guard("price_outlier", price), _guard("near_duplicate", duplicate), _guard("schedule", schedule)]
