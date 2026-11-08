import asyncio
import hashlib
import json
import logging
import os
import re
import socket
import ssl
import threading
import time
from datetime import datetime, timezone
from typing import Optional
from urllib.parse import urlparse

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from app.core import ratelimit
from app.core.config import settings
from app.core.errors import ai_error, AiError, AI_INPUT_INVALID, AI_GENERATION_FAILED
from app.core.ratelimit import rate_limit, actor_of
from app.core.security import require_admin
from app.services.ai import service_metrics, system_probe
from app.services.ai.ai_call import ai_invoke, provider_status
from app.services.ai.event_content import data_block, new_delimiter, message_text

log = logging.getLogger("ai.admin")

# every route in this file is for administrators; the token is verified here, not only at the gateway
router = APIRouter(dependencies=[Depends(require_admin)])


def _actor(request: Request) -> str:
    return actor_of(request, getattr(request.state, "claims", None))


# risk scoring and the assistant live in admin_intel.py (same paths, mounted below)


# AI provider health
PROBE_CACHE_SECONDS = 600
PROBE_FAILURE_CACHE_SECONDS = 60
_probe_lock = threading.Lock()
_probe_cache: dict = {"result": None, "until": 0.0}


def reset_probe_cache() -> None:
    with _probe_lock:
        _probe_cache.update(result=None, until=0.0)


@router.get("/ai/health")
def get_ai_health(http: Request, probe: int = 0):
    """Passive status of the model providers; ?probe=1 makes ONE light call (cached 10 minutes, rate limited)."""
    result = None
    if probe == 1:
        with _probe_lock:
            if _probe_cache["result"] is not None and time.time() < _probe_cache["until"]:
                result = _probe_cache["result"]
        if result is None:
            ratelimit.check("admin_assistant", _actor(http), True)  # a cached answer costs nothing, a real call counts
            checked_at = datetime.now(timezone.utc).isoformat()
            try:
                ai_invoke("ai_probe", [("human", "ping")], actor=_actor(http))
                result, ttl = {"status": "ok", "checked_at": checked_at}, PROBE_CACHE_SECONDS
            except Exception as failure:  # the probe reports, it does not raise
                code = failure.code if isinstance(failure, AiError) else type(failure).__name__[:60]
                result, ttl = {"status": "failed", "checked_at": checked_at, "error_code": code}, PROBE_FAILURE_CACHE_SECONDS
            with _probe_lock:
                _probe_cache.update(result=result, until=time.time() + ttl)
    return {"providers": provider_status(), "probe": result}


# ssl status
def _allowed_ssl_domains() -> list:
    return [d.strip().lower() for d in (settings.alert_ssl_domains or "").split(",") if d.strip()]


@router.get("/ssl-status")
def get_ssl_status(domain: str = None):
    """Kiểm tra thực tế trạng thái SSL/TLS của hệ thống (chỉ các tên miền của hệ thống trong ALERT_SSL_DOMAINS)"""

    allowed = _allowed_ssl_domains()
    raw_domain = domain or (allowed[0] if allowed else "")
    check_domain = raw_domain.strip() if raw_domain else ""
    if "://" in check_domain:
        check_domain = check_domain.split("://", 1)[1]
    check_domain = check_domain.split("/")[0].split(":")[0].strip().lower()

    # the server connects to this name, so an arbitrary one (or an IP) would let a caller probe other hosts
    if check_domain and check_domain not in allowed:
        raise HTTPException(status_code=422, detail={"code": "DOMAIN_NOT_ALLOWED",
                                                      "message": "Tên miền không nằm trong danh sách được phép kiểm tra."})

    # if checking localhost or IP address
    if not check_domain or check_domain in ["localhost", "127.0.0.1"] or check_domain.startswith("192."):
        return {
            "has_ssl": False,
            "domain": check_domain or "localhost",
            "protocol": "HTTP",
            "port": 8888,
            "status": "DEV_HTTP_NO_SSL",
            "issuer": "None (Chưa kích hoạt SSL trên Localhost)",
            "remaining_days": 0,
            "expire_date": "N/A",
            "message": "Hệ thống đang chạy trên môi trường phát triển (Localhost HTTP). SSL/TLS chỉ hoạt động khi gắn tên miền và chạy HTTPS qua cổng 443."
        }

    try:
        context = ssl.create_default_context()
        with socket.create_connection((check_domain, 443), timeout=2.5) as sock:
            with context.wrap_socket(sock, server_hostname=check_domain) as ssock:
                cert = ssock.getpeercert()
                protocol = ssock.version() or "TLS"  # what the handshake really negotiated
                try:
                    port = int(ssock.getpeername()[1])
                except Exception:
                    port = 443
                expire_date = datetime.strptime(cert["notAfter"], "%b %d %H:%M:%S %Y %Z").replace(tzinfo=timezone.utc)
                remaining_days = (expire_date - datetime.now(timezone.utc)).days
                issuer_dict = dict(x[0] for x in cert.get("issuer", []))
                issuer_name = issuer_dict.get("commonName", issuer_dict.get("organizationName", "Unknown CA"))
    except Exception as failure:
        log.warning("TLS check of %s failed: %s", check_domain, type(failure).__name__)
        return {
            "has_ssl": False,
            "domain": check_domain,
            "protocol": "HTTP / KHÔNG KẾT NỐI ĐƯỢC CỔNG 443",
            "port": 443,
            "status": "NO_SSL_OR_UNREACHABLE",
            "issuer": "Chưa có chứng chỉ hợp lệ",
            "remaining_days": 0,
            "expire_date": "N/A",
            "message": f"Không thể thiết lập kết nối TLS với domain '{check_domain}' trên cổng 443."
        }

    if remaining_days < 0:
        status, message = "EXPIRED", f"Chứng chỉ SSL/TLS đã hết hạn cách đây {-remaining_days} ngày."
    elif remaining_days <= 15:
        status, message = "EXPIRING_SOON", f"Chứng chỉ SSL/TLS sắp hết hạn. Còn {remaining_days} ngày."
    else:
        status, message = "ACTIVE_VALID", f"Chứng chỉ SSL/TLS đang hoạt động hợp lệ. Còn {remaining_days} ngày."
    return {
        "has_ssl": True,
        "domain": check_domain,
        "protocol": protocol,
        "port": port,
        "status": status,
        "issuer": issuer_name,
        "remaining_days": remaining_days,
        "expire_date": expire_date.strftime("%d/%m/%Y"),
        "message": message,
    }


# system health
def _as_int(value) -> Optional[int]:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _eureka_status(raw) -> str:
    value = str(raw or "").upper()
    if value == "UP":
        return "UP"
    if value in ("DOWN", "OUT_OF_SERVICE"):
        return "DOWN"
    return "UNKNOWN"


async def _head_latency(client, url: str) -> Optional[int]:
    """Round-trip milliseconds of a HEAD request. Any HTTP answer below 500 proves the service is reachable (a protected
    or route-less root answers 401/403/404 and that is still a real measurement); no answer or a 5xx gives None."""
    if not url:
        return None
    started = time.monotonic()
    try:
        response = await client.head(url, timeout=1.5)
    except Exception:
        return None
    if response.status_code >= 500:
        return None
    return int((time.monotonic() - started) * 1000)


async def _no_metrics() -> dict:
    return {"cpuLoad": None, "memoryUsage": None, "uptime": None}


async def _bounded(function, fallback: dict) -> dict:
    try:
        return await asyncio.wait_for(asyncio.to_thread(function), system_probe.CHECK_TIMEOUT_SECONDS)
    except Exception:
        return fallback


def _eureka_entry(status: str, latency: Optional[int] = None) -> dict:
    try:
        port = urlparse(settings.eureka_server).port
    except Exception:
        port = None
    return {"name": "eureka", "port": port, "type": "Eureka Naming Registry", "status": status, "uptime": None,
            "latencyMs": latency, "memoryUsage": None, "cpuLoad": None}


@router.get("/system-health")
async def get_system_health():
    """Services from Eureka plus the checks this service can really make. A value that cannot be measured is null."""
    eureka_url = f"{settings.eureka_server.rstrip('/')}/apps"
    entries = []
    eureka = _eureka_entry("DOWN")
    try:
        started = time.monotonic()
        async with httpx.AsyncClient() as client:
            response = await client.get(eureka_url, headers={"Accept": "application/json"}, timeout=5.0)
            if response.status_code != 200:
                raise ValueError("eureka status")
            elapsed = int((time.monotonic() - started) * 1000)
            apps = response.json().get("applications", {}).get("application", [])
            if isinstance(apps, dict):
                apps = [apps]
            found = []
            for app in apps:
                if not isinstance(app, dict):
                    continue
                instances = app.get("instance", [])
                if isinstance(instances, dict):
                    instances = [instances]
                instances = [i for i in instances if isinstance(i, dict)]
                if not instances:
                    continue
                inst = instances[0]
                port = inst.get("port")
                metadata = inst.get("metadata") if isinstance(inst.get("metadata"), dict) else {}
                found.append((str(app.get("name", "UNKNOWN")).lower(), _eureka_status(inst.get("status")),
                              _as_int(port.get("$")) if isinstance(port, dict) else None, inst.get("homePageUrl") or "",
                              _as_int(metadata.get("management.port"))))
            latencies = await asyncio.gather(*[_head_latency(client, f[3]) for f in found])
            # CPU, memory and uptime come from each service's Actuator port; a service that does not answer keeps nulls
            metrics = await asyncio.gather(*[
                service_metrics.fetch(client, urlparse(f[3]).hostname or "", f[4]) if not f[0].startswith("ai-assistant")
                else _no_metrics() for f in found])
            registry_host = urlparse(settings.eureka_server).hostname or ""
            eureka_metrics = await service_metrics.fetch(client, registry_host)
        for (name, status, port, _home, _mgmt), latency, measured in zip(found, latencies, metrics):
            if name.startswith("ai-assistant"):
                entry = system_probe.self_entry(port)  # this process: its own uptime and memory
                entry.update(name=name, status=status, latencyMs=latency)
            else:
                entry = {"name": name, "port": port, "type": "Spring Boot Service", "status": status, "uptime": measured["uptime"],
                         "latencyMs": latency, "memoryUsage": measured["memoryUsage"], "cpuLoad": measured["cpuLoad"]}
            entries.append(entry)
        eureka = _eureka_entry("UP", elapsed)
        eureka.update(uptime=eureka_metrics["uptime"], memoryUsage=eureka_metrics["memoryUsage"], cpuLoad=eureka_metrics["cpuLoad"])
    except Exception as failure:
        log.warning("Eureka registry could not be read: %s", type(failure).__name__)
        entries = []  # nothing derived from Eureka is shown; the failure itself is the eureka entry below

    if not any(e["name"].startswith("ai-assistant") for e in entries):
        entries.append(system_probe.self_entry())  # this code is running
    postgres, redis_entry = await asyncio.gather(
        _bounded(system_probe.check_postgres, system_probe.down_entry("postgres", "PostgreSQL")),
        _bounded(system_probe.check_redis, system_probe.down_entry("redis", "Redis")),
    )
    entries.extend([postgres, redis_entry, eureka])
    return entries


# system logs
_TOKEN_PATTERNS = [
    (re.compile(r"eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*"), "[token]"),
    (re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}"), r"\1 [token]"),
    (re.compile(r"(?i)\b(token|access_token|refresh_token|password|passwd|secret|api[_-]?key|authorization)(\s*[=:]\s*)[^\s&,;\"']+"), r"\1\2[hidden]"),
    (re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"), "[email]"),
    (re.compile(r"\b[A-Za-z0-9+/_-]{40,}={0,2}"), "[hidden]"),
]

_LOG_TIME = re.compile(r"(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})")


def mask_sensitive(line: str) -> str:
    """Hides token-like strings, credentials in key=value pairs and e-mail addresses before a log line leaves the server."""
    for pattern, replacement in _TOKEN_PATTERNS:
        line = pattern.sub(replacement, line)
    return line


def _log_time(line: str) -> Optional[str]:
    match = _LOG_TIME.search(line)
    return f"{match.group(1)}T{match.group(2)}" if match else None


_LOG_LEVEL = re.compile(r"^\S+\s+(ERROR|WARN|INFO|DEBUG|TRACE)")
_LOG_START = re.compile(r"^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}")
MAX_LOG_ENTRIES = 500
_TAIL_BYTES = 60000


def _tail_lines(path: str, count: int) -> list:
    size = os.path.getsize(path)
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        if size > _TAIL_BYTES:
            f.seek(size - _TAIL_BYTES)
            f.readline()  # discard partial line
        return f.readlines()[-max(1, count):]


def _log_entry(line_str: str, service: str, status: str) -> dict:
    return {
        "id": hashlib.sha1((service + line_str).encode("utf-8")).hexdigest()[:12],
        "time": _log_time(line_str), "service": service, "event": line_str[:180], "status": status, "details": line_str,
        "operator": None, "ipAddress": None, "endpoint": None,
    }


def _logs_from_directory(directory: str) -> dict:
    """One <service>.log per service (Spring Boot format). The service name is the file name, the level is the level token of
    the line, so nothing is guessed from the words in the message. Stack-trace lines are left out; the first line of an error
    is the entry."""
    try:
        files = sorted(e.path for e in os.scandir(directory) if e.is_file() and e.name.endswith(".log"))
    except Exception as failure:
        log.warning("System log folder unreadable: %s", type(failure).__name__)
        return {"entries": [], "source": {"status": "unavailable"}}
    entries = []
    for path in files:
        service = os.path.basename(path)[:-4]
        try:
            lines = _tail_lines(path, settings.ai_system_log_max_lines)
        except Exception as failure:
            log.warning("System log %s unreadable: %s", service, type(failure).__name__)
            continue
        for line in lines:
            if not _LOG_START.match(line):
                continue
            line_str = mask_sensitive(line.strip())
            level = _LOG_LEVEL.match(line_str)
            if level is None or level.group(1) in ("DEBUG", "TRACE"):
                continue
            entries.append(_log_entry(line_str, service, "ERROR" if level.group(1) == "ERROR" else "WARN" if level.group(1) == "WARN" else "INFO"))
    entries.sort(key=lambda e: e["time"] or "")
    entries = entries[-MAX_LOG_ENTRIES:]
    return {"entries": entries[::-1], "source": {"status": "ok" if entries else "empty"}}  # newest first


@router.get("/system-logs")
def get_system_logs():
    """Real log lines only: {entries, source:{status: ok|empty|unavailable}}. No placeholder entries."""
    if settings.ai_system_log_dir:
        return _logs_from_directory(settings.ai_system_log_dir)
    log_path = settings.ai_system_log_file  # exactly one configured file, never a path from the request
    try:
        if not log_path or not os.path.isfile(log_path):
            return {"entries": [], "source": {"status": "unavailable"}}
        lines = _tail_lines(log_path, settings.ai_system_log_max_lines)
    except Exception as failure:
        log.warning("System log unreadable: %s", type(failure).__name__)
        return {"entries": [], "source": {"status": "unavailable"}}

    entries = []
    for line in lines:
        line_str = mask_sensitive(line.strip())
        if not line_str:
            continue
        lowered = line_str.lower()
        is_error = "error" in lowered or "exception" in lowered or "fail" in lowered
        is_warn = "warn" in lowered or "timeout" in lowered or "denied" in lowered
        status = "ERROR" if is_error else "WARN" if is_warn else "INFO"
        service_name = "event-service"
        if "gateway" in lowered: service_name = "api-gateway"
        elif "ticket" in lowered: service_name = "ticket-service"
        elif "booking" in lowered: service_name = "booking-service"
        elif "auth" in lowered or "user" in lowered: service_name = "user-service"
        elif "payment" in lowered: service_name = "payment-service"
        entries.append(_log_entry(line_str, service_name, status))
    return {"entries": entries, "source": {"status": "ok" if entries else "empty"}}


# admin intelligence: risk scoring, KPIs, attention feed, summary, pins, assistant (included last: it needs nothing above)
from app.api.endpoints import admin_intel  # noqa: E402

router.include_router(admin_intel.router)
