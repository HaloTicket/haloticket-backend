"""CPU, bộ nhớ và thời gian chạy của một service Spring Boot, đọc từ cổng metrics Actuator (chỉ trong mạng compose).

Không bịa số liệu: giá trị không đọc được là None. Không hàm nào ở đây ném ngoại lệ. Các service chỉ mở `health` và
`metrics`; cổng quản trị không được publish và gateway từ chối /actuator/** từ bên ngoài.
"""
import asyncio
import logging
from typing import Optional

from app.core.config import settings

log = logging.getLogger("ai.metrics")

TIMEOUT_SECONDS = 1.5
_MB = 1024 * 1024


def format_uptime(seconds: float) -> str:
    """Đổi số giây thành chuỗi dạng "1d 2h 3m" (bỏ phần ngày khi bằng 0)."""
    seconds = int(max(0, seconds))
    days, rest = divmod(seconds, 86400)
    hours, rest = divmod(rest, 3600)
    minutes = rest // 60
    return f"{days}d {hours}h {minutes}m" if days else f"{hours}h {minutes}m"


async def _value(client, base: str, metric: str, tag: Optional[str] = None) -> Optional[float]:
    """Đọc một metric Actuator (VALUE hoặc TOTAL); trả None nếu HTTP khác 200, không có số đo hoặc có lỗi."""
    params = {"tag": tag} if tag else None
    try:
        response = await client.get(f"{base}/actuator/metrics/{metric}", params=params, timeout=TIMEOUT_SECONDS)
        if response.status_code != 200:
            return None
        for measurement in response.json().get("measurements", []):
            if measurement.get("statistic") in ("VALUE", "TOTAL"):
                return float(measurement["value"])
    except Exception:
        return None
    return None


async def fetch(client, host: str, port: Optional[int] = None) -> dict:
    """Trả {cpuLoad: '12.3%', memoryUsage: '236 MB', uptime: '1h 5m'}, mỗi trường là None khi không đọc được; bộ nhớ là heap cộng non-heap."""
    if not host:
        return {"cpuLoad": None, "memoryUsage": None, "uptime": None}
    base = f"http://{host}:{port or settings.ai_actuator_port}"
    cpu, heap, nonheap, up = await asyncio.gather(
        _value(client, base, "process.cpu.usage"),
        _value(client, base, "jvm.memory.used", "area:heap"),
        _value(client, base, "jvm.memory.used", "area:nonheap"),
        _value(client, base, "process.uptime"),
    )
    memory = None if heap is None else f"{int((heap + (nonheap or 0)) / _MB)} MB"
    return {
        "cpuLoad": None if cpu is None or cpu < 0 else f"{cpu * 100:.1f}%",
        "memoryUsage": memory,
        "uptime": None if up is None else format_uptime(up),
    }
