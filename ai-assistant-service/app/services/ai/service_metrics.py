"""CPU, memory and uptime of a Spring Boot service, read from its Actuator metrics port (inside the compose network only).

Nothing is invented: a value that cannot be read is None. Nothing here raises. The services expose only `health` and
`metrics`; their management port is never published and the gateway refuses /actuator/** from outside.
"""
import asyncio
import logging
from typing import Optional

from app.core.config import settings

log = logging.getLogger("ai.metrics")

TIMEOUT_SECONDS = 1.5
_MB = 1024 * 1024


def format_uptime(seconds: float) -> str:
    seconds = int(max(0, seconds))
    days, rest = divmod(seconds, 86400)
    hours, rest = divmod(rest, 3600)
    minutes = rest // 60
    return f"{days}d {hours}h {minutes}m" if days else f"{hours}h {minutes}m"


async def _value(client, base: str, metric: str, tag: Optional[str] = None) -> Optional[float]:
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
    """{cpuLoad: '12.3%', memoryUsage: '236 MB', uptime: '1h 5m'}, each None when it could not be read."""
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
