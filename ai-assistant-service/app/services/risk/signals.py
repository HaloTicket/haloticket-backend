"""Layer B: signals from data. A signal that cannot be read is NOT_ASSESSED: it adds no points and never counts as safe.

The data comes in through a SignalSource (functions), so tests supply fixtures and production supplies the HTTP and SQL
readers from app.services.admin_intel.upstream. Every reader may raise; nothing here lets that escape.
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
MAX_DUPLICATE_CANDIDATES = 500


@dataclass
class SignalSource:
    user: Callable[[str], dict]                 # user-service admin detail: createdAt, ekycStatus
    approvals: Callable[[], list]               # every event (EventResponse-shaped dicts)
    category_prices: Callable[[str], list]      # min_price of APPROVED events of a category (v_events)


def _finding(code: str, weight: int, message: str, severity: str = "MEDIUM", evidence: Optional[list] = None) -> dict:
    return {"layer": "SIGNAL", "code": code, "severity": severity, "weight": weight, "message": message, "evidence": evidence or []}


def _parse_time(value) -> Optional[datetime]:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _shingles(text: str) -> set:
    words = re.findall(r"\w+", (text or "").lower())
    if len(words) < 3:
        return {" ".join(words)} if words else set()
    return {" ".join(words[i:i + 3]) for i in range(len(words) - 2)}


def similarity(a: str, b: str) -> float:
    sa, sb = _shingles(a), _shingles(b)
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / len(sa | sb)


def _guard(name: str, function: Callable) -> dict:
    try:
        finding = function()
    except Exception as failure:
        log.warning("Signal %s not assessed: %s", name, type(failure).__name__)
        return {"name": name, "status": "not_assessed"}
    return {"name": name, "status": "assessed", "finding": finding}


def evaluate(event: dict, texts: dict, source: SignalSource, now: Optional[datetime] = None) -> list:
    """The six signals for one event (EventResponse-shaped dict). Returns [{name, status, finding?}]."""
    now = now or datetime.now(timezone.utc)
    organizer = event.get("organizer") if isinstance(event.get("organizer"), dict) else {}
    user_id = organizer.get("userId")
    event_id = str(event.get("id") or "")
    cache: dict = {}

    def user() -> dict:
        if "user" not in cache:
            if not user_id:
                raise LookupError("no organizer id")
            cache["user"] = source.user(str(user_id))
        return cache["user"]

    def events() -> list:
        if "events" not in cache:
            cache["events"] = source.approvals()
        return cache["events"]

    def account():
        created = _parse_time(user().get("createdAt"))
        if created is None:
            raise LookupError("no createdAt")
        age = now - created
        if age < timedelta(days=7):
            return _finding("organizer_account_new", 12, "Tài khoản ban tổ chức mới tạo chưa đầy 7 ngày.", "MEDIUM",
                            [{"field": "organizer", "text": f"tạo {age.days} ngày trước"}])
        if age < timedelta(days=30):
            return _finding("organizer_account_young", 6, "Tài khoản ban tổ chức tạo chưa đầy 30 ngày.", "MEDIUM",
                            [{"field": "organizer", "text": f"tạo {age.days} ngày trước"}])
        return None

    def ekyc():
        status = user().get("ekycStatus")
        if status is None:
            raise LookupError("no ekyc status")
        if str(status).upper() != "APPROVED":
            return _finding("organizer_ekyc_not_approved", 15, "Ban tổ chức chưa được xác minh eKYC.", "HIGH",
                            [{"field": "organizer", "text": f"eKYC: {status}"}])
        return None

    def history():
        if not user_id:
            raise LookupError("no organizer id")
        others = [e for e in events()
                  if isinstance(e.get("organizer"), dict) and e["organizer"].get("userId") == user_id and str(e.get("id")) != event_id]
        approved = sum(1 for e in others if e.get("eventApprovalStatus") == "APPROVED")
        rejected = sum(1 for e in others if e.get("eventApprovalStatus") == "REJECTED")
        if rejected >= 2 and rejected > approved:
            return _finding("organizer_history_rejected", 15, "Ban tổ chức từng bị từ chối nhiều sự kiện hơn số sự kiện được duyệt.", "HIGH",
                            [{"field": "organizer", "text": f"đã duyệt {approved}, từ chối {rejected}"}])
        return None

    def price():
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
                            [{"field": "price", "text": f"{int(price_now)} so với trung vị {int(median)}"}])
        return None

    def duplicate():
        mine = f"{texts.get('title', '')} {texts.get('description', '')}"
        best_score, best_event = 0.0, None
        for other in events()[:MAX_DUPLICATE_CANDIDATES]:
            if str(other.get("id")) == event_id:
                continue
            other_organizer = other.get("organizer") if isinstance(other.get("organizer"), dict) else {}
            if user_id and other_organizer.get("userId") == user_id and other.get("eventApprovalStatus") == "APPROVED":
                continue  # a show the same organiser already had approved (a series, a new season) is not a copy of someone else
            other_text = f"{other.get('title', '')} {other.get('descriptionText') or other.get('description') or ''}"
            score = similarity(mine, other_text)
            if score > best_score:
                best_score, best_event = score, other
        if best_event is not None and best_score >= DUPLICATE_THRESHOLD:
            return _finding("near_duplicate", 25, "Nội dung gần trùng với một sự kiện khác.", "HIGH",
                            [{"field": "description", "text": f"{int(best_score * 100)}% giống '{str(best_event.get('title'))[:80]}'"}])
        return None

    def schedule():
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
                        "MEDIUM", [{"field": "schedule", "text": "; ".join(issues)}])

    return [_guard("organizer_account", account), _guard("organizer_ekyc", ekyc), _guard("organizer_history", history),
            _guard("price_outlier", price), _guard("near_duplicate", duplicate), _guard("schedule", schedule)]
