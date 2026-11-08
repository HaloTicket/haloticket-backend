"""Cache of chat-search answers: Redis when REDIS_URL is set and the `redis` package exists, else a 200-entry LRU in
this process. Keyed by the normalised question and top_k. Cache trouble never fails a request."""
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
_lru: "OrderedDict[str, tuple]" = OrderedDict()  # key -> (expires_at, json text)
_redis_client = None
_redis_url = None
_breaker_until = 0.0
_redis_factory = None  # tests may set a callable(url) -> client


def normalize(query: str) -> str:
    return " ".join((query or "").lower().split())


def make_key(query: str, top_k: int) -> str:
    digest = hashlib.sha256(normalize(query).encode("utf-8")).hexdigest()[:32]
    return f"aisearch:v1:{int(top_k)}:{digest}"


def _redis():
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
                import redis  # lazy: the package may be absent
                client = redis.Redis.from_url(url, socket_connect_timeout=0.5, socket_timeout=0.5)
        except Exception as failure:
            log.warning("Search cache: Redis unavailable (%s); using in-process cache", type(failure).__name__)
            return None
        _redis_client, _redis_url = client, url
        return client


def _tripped(failure: Exception) -> None:
    global _breaker_until
    _breaker_until = time.time() + _BREAKER_SECONDS
    log.warning("Search cache: Redis failed (%s); in-process cache for %ss", type(failure).__name__, _BREAKER_SECONDS)


def get(key: str):
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
    global _redis_client, _redis_url, _breaker_until, _redis_factory
    with _lock:
        _lru.clear()
    _redis_client = _redis_url = None
    _breaker_until = 0.0
    _redis_factory = None
