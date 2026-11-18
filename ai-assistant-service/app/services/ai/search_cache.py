"""Cache câu trả lời chat-search: dùng Redis khi có REDIS_URL và có gói `redis`, nếu không thì LRU 200 mục trong tiến trình này.
Khóa theo câu hỏi đã chuẩn hóa và top_k. Lỗi cache không bao giờ làm hỏng yêu cầu."""
import hashlib
import json
import logging
import threading
import time
from collections import OrderedDict

from app.core.config import settings

log = logging.getLogger("ai.searchcache")

LRU_MAX = 200
_BREAKER_SECONDS = 60

_lock = threading.Lock()
_lru: "OrderedDict[str, tuple]" = OrderedDict()  # khóa -> (thời điểm hết hạn, văn bản json)
_redis_client = None
_redis_url = None
_breaker_until = 0.0
_redis_factory = None  # kiểm thử có thể gán một callable(url) -> client


def normalize(query: str) -> str:
    """Chuẩn hóa câu hỏi: chữ thường, gộp khoảng trắng."""
    return " ".join((query or "").lower().split())


def make_key(query: str, top_k: int) -> str:
    """Sinh khóa cache từ câu hỏi đã chuẩn hóa (băm SHA-256) và top_k."""
    digest = hashlib.sha256(normalize(query).encode("utf-8")).hexdigest()[:32]
    return f"aisearch:v1:{int(top_k)}:{digest}"


def _redis():
    """Trả client Redis dùng chung (tạo lười, có khóa luồng); None khi chưa cấu hình REDIS_URL hoặc không kết nối được."""
    global _redis_client, _redis_url
    url = settings.redis_url
    if not url:
        return None
    with _lock:
        if _redis_client is not None and _redis_url == url:
            return _redis_client
        try:
            if _redis_factory is not None:
                client = _redis_factory(url)
            else:
                import redis  # nạp lười: gói có thể không được cài
                client = redis.Redis.from_url(url, socket_connect_timeout=0.5, socket_timeout=0.5)
        except Exception as failure:
            log.warning("Search cache: Redis unavailable (%s); using in-process cache", type(failure).__name__)
            return None
        _redis_client, _redis_url = client, url
        return client


def _tripped(failure: Exception) -> None:
    """Mở cầu dao: tạm bỏ Redis trong 60 giây sau một lỗi và ghi log."""
    global _breaker_until
    _breaker_until = time.time() + _BREAKER_SECONDS
    log.warning("Search cache: Redis failed (%s); in-process cache for %ss", type(failure).__name__, _BREAKER_SECONDS)


def get(key: str):
    """Đọc kết quả đã cache theo khóa: thử Redis trước (nếu cầu dao đóng), rồi tới LRU trong tiến trình; mục hết hạn bị xóa. Trả None khi không có."""
    if time.time() >= _breaker_until:
        client = _redis()
        if client is not None:
            try:
                raw = client.get(key)
                return json.loads(raw) if raw else None
            except Exception as failure:
                _tripped(failure)
    with _lock:
        item = _lru.get(key)
        if item is None:
            return None
        if item[0] <= time.time():
            _lru.pop(key, None)
            return None
        _lru.move_to_end(key)
        return json.loads(item[1])


def put(key: str, value: dict) -> None:
    """Lưu kết quả vào Redis (kèm TTL) hoặc LRU trong tiến trình; không làm gì khi ai_search_cache_seconds <= 0."""
    ttl = int(settings.ai_search_cache_seconds)
    if ttl <= 0:
        return
    payload = json.dumps(value, ensure_ascii=False)
    if time.time() >= _breaker_until:
        client = _redis()
        if client is not None:
            try:
                client.set(key, payload, ex=ttl)
                return
            except Exception as failure:
                _tripped(failure)
    with _lock:
        _lru[key] = (time.time() + ttl, payload)
        _lru.move_to_end(key)
        while len(_lru) > LRU_MAX:
            _lru.popitem(last=False)


def reset_for_tests() -> None:
    """Đặt lại toàn bộ trạng thái cache và client Redis (chỉ dùng trong kiểm thử)."""
    global _redis_client, _redis_url, _breaker_until, _redis_factory
    with _lock:
        _lru.clear()
    _redis_client = _redis_url = None
    _breaker_until = 0.0
    _redis_factory = None
