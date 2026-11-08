"""Checks that this service can really make for /api/admin/system-health: itself, Postgres, Redis.

Nothing here is invented: a value that cannot be measured is None. No function raises, none logs a secret.
"""
import logging
import time
from typing import Optional
from urllib.parse import urlparse

from sqlalchemy import text

from app.core.config import settings
from app.db import session

log = logging.getLogger("ai.probe")

CHECK_TIMEOUT_SECONDS = 2.0
_PROCESS_STARTED = time.time()


def process_uptime(now: Optional[float] = None) -> str:
    seconds = int((time.time() if now is None else now) - _PROCESS_STARTED)
    days, rest = divmod(max(0, seconds), 86400)
    hours, rest = divmod(rest, 3600)
    minutes = rest // 60
    if days:
        return f"{days}d {hours}h {minutes}m"
    return f"{hours}h {minutes}m"


def process_memory(status_path: str = "/proc/self/status") -> Optional[str]:
    """Resident memory of this process from VmRSS, or None when /proc is not readable (not Linux)."""
    try:
        with open(status_path, "r", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("VmRSS:"):
                    return f"{int(line.split()[1]) // 1024} MB"
    except Exception:
        return None
    return None


def self_entry(port: Optional[int] = None) -> dict:
    return {"name": "ai-assistant-service", "port": port if port is not None else settings.port, "type": "FastAPI Service",
            "status": "UP", "uptime": process_uptime(), "latencyMs": None, "memoryUsage": process_memory(), "cpuLoad": None}


def _entry(name: str, port, kind: str, status: str, latency=None) -> dict:
    return {"name": name, "port": port, "type": kind, "status": status, "uptime": None, "latencyMs": latency,
            "memoryUsage": None, "cpuLoad": None}


def down_entry(name: str, kind: str) -> dict:
    """What is reported when a check ran past its time budget."""
    return _entry(name, None, kind, "DOWN")


def check_postgres() -> dict:
    """SELECT 1 through the shared engine (None when DATABASE_URL is not configured: status UNKNOWN)."""
    engine = session.engine
    if engine is None:
        return _entry("postgres", None, "PostgreSQL", "UNKNOWN")
    port = getattr(getattr(engine, "url", None), "port", None) or 5432
    started = time.monotonic()
    try:
        with engine.connect() as conn:
            conn.execute(text("SET LOCAL statement_timeout = 2000"))
            conn.execute(text("SELECT 1"))
            conn.rollback()
    except Exception as failure:
        log.warning("Postgres check failed: %s", type(failure).__name__)
        return _entry("postgres", port, "PostgreSQL", "DOWN")
    return _entry("postgres", port, "PostgreSQL", "UP", int((time.monotonic() - started) * 1000))


def make_redis(url: str):
    import redis  # lazy: the package may be absent
    return redis.Redis.from_url(url, socket_connect_timeout=1, socket_timeout=1)


def check_redis() -> dict:
    url = settings.redis_url
    if not url:
        return _entry("redis", None, "Redis", "UNKNOWN")
    try:
        port = urlparse(url).port or 6379
    except Exception:
        port = None
    started = time.monotonic()
    try:
        client = make_redis(url)
        client.ping()
    except ImportError:
        return _entry("redis", port, "Redis", "UNKNOWN")
    except Exception as failure:
        log.warning("Redis check failed: %s", type(failure).__name__)
        return _entry("redis", port, "Redis", "DOWN")
    return _entry("redis", port, "Redis", "UP", int((time.monotonic() - started) * 1000))
