"""Các phép kiểm tra mà service này thực sự làm được cho /api/admin/system-health: chính nó, Postgres, Redis.

Không bịa số liệu: giá trị không đo được là None. Không hàm nào ném ngoại lệ, không hàm nào ghi log bí mật.
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
    """Thời gian chạy của tiến trình, dạng "1d 2h 3m" (bỏ phần ngày khi bằng 0). `now` dùng để kiểm thử."""
    seconds = int((time.time() if now is None else now) - _PROCESS_STARTED)
    days, rest = divmod(max(0, seconds), 86400)
    hours, rest = divmod(rest, 3600)
    minutes = rest // 60
    if days:
        return f"{days}d {hours}h {minutes}m"
    return f"{hours}h {minutes}m"


def process_memory(status_path: str = "/proc/self/status") -> Optional[str]:
    """Bộ nhớ thường trú (resident) của tiến trình theo VmRSS; None khi không đọc được /proc (không phải Linux)."""
    try:
        with open(status_path, "r", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("VmRSS:"):
                    return f"{int(line.split()[1]) // 1024} MB"
    except Exception:
        return None
    return None


_cpu_sample = {"cpu": 0.0, "at": 0.0}


def process_cpu() -> Optional[str]:
    """Mức dùng CPU của tiến trình từ lần gọi trước (từ lúc khởi động ở lần đầu), tính theo phần trăm của một lõi; None nếu quãng đo dưới 0,5 giây."""
    now, used = time.monotonic(), time.process_time()
    previous_at = _cpu_sample["at"] or (now - max(1.0, time.time() - _PROCESS_STARTED))
    previous_cpu = _cpu_sample["cpu"]
    _cpu_sample.update(cpu=used, at=now)
    elapsed = now - previous_at
    if elapsed < 0.5:
        return None
    return f"{max(0.0, (used - previous_cpu) / elapsed) * 100:.1f}%"


def self_entry(port: Optional[int] = None) -> dict:
    """Mục trạng thái của chính service này (luôn UP) kèm uptime, bộ nhớ và CPU đo được."""
    return {"name": "ai-assistant-service", "port": port if port is not None else settings.port, "type": "FastAPI Service",
            "status": "UP", "uptime": process_uptime(), "latencyMs": None, "memoryUsage": process_memory(), "cpuLoad": process_cpu()}


def _entry(name: str, port, kind: str, status: str, latency=None) -> dict:
    """Dựng một mục trạng thái cho danh sách system-health; các số liệu chưa đo được để None."""
    return {"name": name, "port": port, "type": kind, "status": status, "uptime": None, "latencyMs": latency,
            "memoryUsage": None, "cpuLoad": None}


def down_entry(name: str, kind: str) -> dict:
    """Mục báo cáo khi một phép kiểm tra chạy quá ngân sách thời gian: trạng thái DOWN."""
    return _entry(name, None, kind, "DOWN")


def check_postgres() -> dict:
    """Chạy SELECT 1 qua engine dùng chung và đo độ trễ (UNKNOWN khi DATABASE_URL chưa cấu hình, DOWN khi lỗi)."""
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
    """Tạo client Redis với timeout 1 giây."""
    import redis  # nạp lười: gói có thể không được cài
    return redis.Redis.from_url(url, socket_connect_timeout=1, socket_timeout=1)


def check_redis() -> dict:
    """Ping Redis và đo độ trễ: UP khi ping được, DOWN khi lỗi, UNKNOWN khi chưa cấu hình REDIS_URL hoặc thiếu gói redis."""
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
