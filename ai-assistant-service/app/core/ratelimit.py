"""Giới hạn tần suất theo cửa sổ cố định và ngân sách gọi mô hình mỗi ngày.

Bộ đếm nằm trong Redis (INCR + EXPIRE trong một pipeline, khóa `aiq:<bucket>:<window>:<actor>`) khi đã đặt REDIS_URL và có gói
`redis`; nếu không, hoặc khi Redis đang lỗi (cầu dao ngắt 60 giây), bộ đếm nằm trong dict của tiến trình (an toàn luồng).
Lỗi Redis không bao giờ chặn người dùng. Thứ tự trên một route: dependency xác thực, rồi `rate_limit(...)`, rồi kiểm tra body.
"""
import hashlib
import ipaddress
import logging
import threading
import time
from datetime import datetime, timezone
from typing import Optional

from fastapi import Request

from app.core.config import settings
from app.core.errors import ai_error, AI_RATE_LIMITED, AI_BUDGET_EXHAUSTED
from app.core.security import get_optional_claims

log = logging.getLogger("ai.ratelimit")

_MIN, _HOUR, _DAY = 60, 3600, 86400
_BREAKER_SECONDS = 60
_WARN_EVERY = 60


# Định danh người gọi
def _trusted_networks():
    """Đọc danh sách mạng proxy tin cậy (CIDR) từ cấu hình; bỏ qua mục không hợp lệ."""
    nets = []
    for part in (settings.ai_trusted_proxy_cidrs or "").split(","):
        part = part.strip()
        if not part:
            continue
        try:
            nets.append(ipaddress.ip_network(part, strict=False))
        except ValueError:
            log.warning("Ignoring invalid trusted proxy CIDR")
    return nets


def client_ip(request: Request) -> str:
    """IP thật của client: chỉ tin `X-Forwarded-For` khi kết nối đến từ proxy tin cậy, ngược lại dùng IP của kết nối."""
    peer = request.client.host if request.client else ""
    try:
        peer_ip = ipaddress.ip_address(peer)
    except ValueError:
        return peer or "unknown"
    if any(peer_ip in net for net in _trusted_networks() if net.version == peer_ip.version):
        forwarded = request.headers.get("x-forwarded-for", "")
        first = forwarded.split(",")[0].strip() if forwarded else ""
        if first:
            try:
                return str(ipaddress.ip_address(first))
            except ValueError:
                pass
    return str(peer_ip)


def _hash(value: str) -> str:
    """Băm SHA-256 rút gọn 12 ký tự để không đưa định danh gốc vào khóa đếm hay nhật ký."""
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:12]


def actor_of(request: Request, claims: Optional[dict]) -> str:
    """Định danh dùng để đếm lượt: `sub:<băm>` nếu đã đăng nhập, ngược lại `ip:<băm>` theo IP client."""
    sub = (claims or {}).get("sub")
    if sub:
        return "sub:" + _hash(str(sub))
    return "ip:" + _hash(client_ip(request))


# Kho bộ đếm
class _MemoryStore:
    """Bộ đếm trong bộ nhớ tiến trình (an toàn luồng), dùng khi không có Redis hoặc Redis đang lỗi."""
    def __init__(self):
        """Tạo kho rỗng cùng khóa luồng."""
        self._lock = threading.Lock()
        self._data = {}
        self._last_purge = 0.0

    def incr(self, key: str, ttl: int) -> int:
        """Tăng bộ đếm của `key` lên 1 và trả về giá trị mới; khóa hết hạn thì đếm lại từ đầu, định kỳ dọn các khóa đã hết hạn."""
        now = time.time()
        with self._lock:
            if now - self._last_purge > 60:
                self._data = {k: v for k, v in self._data.items() if v[1] > now}
                self._last_purge = now
            count, expires = self._data.get(key, (0, 0))
            if expires <= now:
                count, expires = 0, now + ttl
            count += 1
            self._data[key] = (count, expires)
            return count

    def clear(self):
        """Xóa toàn bộ bộ đếm."""
        with self._lock:
            self._data.clear()


_memory = _MemoryStore()
_state_lock = threading.Lock()
_redis_client = None
_redis_url = None
_breaker_until = 0.0
_last_warn = 0.0
_redis_factory = None  # test có thể gán một callable(url) -> client


def _warn(msg: str):
    """Ghi cảnh báo nhưng tối đa một lần mỗi `_WARN_EVERY` giây để không làm ngập nhật ký."""
    global _last_warn
    now = time.time()
    if now - _last_warn >= _WARN_EVERY:
        _last_warn = now
        log.warning(msg)


def _get_redis():
    """Trả về client Redis dùng chung (tạo lười theo `redis_url`); `None` nếu chưa cấu hình, thiếu gói `redis` hoặc không tạo được client."""
    global _redis_client, _redis_url
    url = settings.redis_url
    if not url:
        return None
    with _state_lock:
        if _redis_client is not None and _redis_url == url:
            return _redis_client
        try:
            if _redis_factory is not None:
                client = _redis_factory(url)
            else:
                import redis  # nạp lười: gói có thể không được cài
                client = redis.Redis.from_url(url, socket_connect_timeout=0.5, socket_timeout=0.5)
        except ImportError:
            _warn("redis package not installed; using in-process rate limit counters")
            return None
        except Exception as failure:
            _warn(f"Redis client could not be built ({type(failure).__name__}); in-process counters")
            return None
        _redis_client, _redis_url = client, url
        return client


def _incr(key: str, ttl: int) -> int:
    """Tăng bộ đếm trong Redis; nếu Redis lỗi thì mở cầu dao `_BREAKER_SECONDS` giây và dùng bộ đếm trong bộ nhớ."""
    global _breaker_until
    if time.time() >= _breaker_until:
        client = _get_redis()
        if client is not None:
            try:
                pipe = client.pipeline()
                pipe.incr(key)
                pipe.expire(key, ttl)
                result = pipe.execute()
                return int(result[0])
            except Exception as failure:
                _breaker_until = time.time() + _BREAKER_SECONDS
                _warn(f"Redis counter failed ({type(failure).__name__}); in-process fallback for {_BREAKER_SECONDS}s")
    return _memory.incr(key, ttl)


# Các giới hạn
def _limits_for(bucket: str, authenticated: bool):
    """Danh sách `(nhãn, số giây của cửa sổ, hạn mức)` của nhóm giới hạn; ném `ValueError` nếu tên nhóm không tồn tại."""
    s = settings
    if bucket == "customer_search":
        return [("m", _MIN, s.ai_rl_customer_search_per_minute)]
    if bucket == "customer_chat":
        if authenticated:
            return [("m", _MIN, s.ai_rl_customer_chat_account_per_minute), ("h", _HOUR, s.ai_rl_customer_chat_account_per_hour)]
        return [("m", _MIN, s.ai_rl_customer_chat_ip_per_minute), ("h", _HOUR, s.ai_rl_customer_chat_ip_per_hour)]
    if bucket == "event_content":
        return [("h", _HOUR, s.ai_rl_event_content_per_hour), ("d", _DAY, s.ai_rl_event_content_per_day)]
    if bucket == "voucher_strategy":
        return [("h", _HOUR, s.ai_rl_voucher_strategy_per_hour), ("d", _DAY, s.ai_rl_voucher_strategy_per_day)]
    if bucket == "data_analysis":
        return [("h", _HOUR, s.ai_rl_data_analysis_per_hour), ("d", _DAY, s.ai_rl_data_analysis_per_day)]
    if bucket == "admin_assistant":
        return [("h", _HOUR, s.ai_rl_admin_assistant_per_hour)]
    if bucket == "risk_scoring":
        return [("h", _HOUR, s.ai_rl_risk_scoring_per_hour)]
    if bucket == "dashboard_summary":
        return [("d", _DAY, s.ai_rl_dashboard_summary_per_day)]
    raise ValueError(f"unknown rate limit bucket: {bucket}")


def check(bucket: str, actor: str, authenticated: bool, now: Optional[float] = None) -> None:
    """Tính một lượt cho `actor` trong nhóm `bucket`; vượt hạn mức ở bất kỳ cửa sổ nào thì ném `AI_RATE_LIMITED` kèm số giây nên chờ."""
    now = time.time() if now is None else now
    for label, seconds, limit in _limits_for(bucket, authenticated):
        window = int(now // seconds)
        count = _incr(f"aiq:{bucket}:{label}{window}:{actor}", seconds + 5)
        if count > limit:
            retry = max(1, int((window + 1) * seconds - now) + 1)
            raise ai_error(AI_RATE_LIMITED, retry_after=retry)


def rate_limit(bucket: str):
    """Tạo dependency FastAPI giới hạn tần suất cho nhóm `bucket`; người đã đăng nhập dùng hạn mức theo tài khoản."""
    _limits_for(bucket, False)  # lỗi ngay lúc import nếu gõ sai tên nhóm

    def dependency(request: Request) -> None:
        """Lấy claims (nếu chưa có) rồi tính một lượt cho người gọi."""
        claims = getattr(request.state, "claims", None)
        if claims is None:
            claims = get_optional_claims(request)
        check(bucket, actor_of(request, claims), bool((claims or {}).get("sub")))

    return dependency


def consume_budget(units: int = 1, actor: Optional[str] = None) -> None:
    """Trừ `units` lượt khỏi ngân sách gọi mô hình trong ngày (theo UTC); hết ngân sách thì ném `AI_BUDGET_EXHAUSTED` kèm số giây tới nửa đêm.

    Người chưa đăng nhập (`actor` bắt đầu bằng `ip:`) chỉ được dùng tối đa `ai_anonymous_budget_share` của ngân sách: một nhóm IP ẩn danh
    không thể tiêu hết ngân sách của cả hệ thống và khóa luôn người đã đăng nhập. Lượt ẩn danh bị từ chối vì quá phần của mình không
    bị trừ vào ngân sách chung."""
    now = datetime.now(timezone.utc)
    day = now.strftime("%Y%m%d")
    seconds_left = int(86400 - (now.hour * 3600 + now.minute * 60 + now.second))
    anonymous = bool(actor) and actor.startswith("ip:")
    if anonymous:
        anon_cap = int(settings.ai_daily_call_budget * max(0.0, min(1.0, settings.ai_anonymous_budget_share)))
        anon_total = 0
        for _ in range(max(1, units)):
            anon_total = _incr("aiq:budget:anon:" + day, 86400 + 60)
        if anon_total > anon_cap:
            raise ai_error(AI_BUDGET_EXHAUSTED, retry_after=max(1, seconds_left))
    total = 0
    for _ in range(max(1, units)):
        total = _incr("aiq:budget:" + day, 86400 + 60)
    if total > settings.ai_daily_call_budget:
        raise ai_error(AI_BUDGET_EXHAUSTED, retry_after=max(1, seconds_left))


def reset_for_tests() -> None:
    """Đặt lại toàn bộ trạng thái toàn cục (bộ đếm, client Redis, cầu dao); chỉ dùng trong kiểm thử."""
    global _redis_client, _redis_url, _breaker_until, _last_warn, _redis_factory
    _memory.clear()
    _redis_client = _redis_url = None
    _breaker_until = 0.0
    _last_warn = 0.0
    _redis_factory = None
