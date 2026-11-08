"""Best-effort metering of every model attempt into ai_ops.ai_usage (one INSERT per attempt).

The insert is synchronous (a single small INSERT with a 2 s connect timeout); the circuit breaker skips the database for
60 s after a failure, so a down database costs at most one slow call per minute. record() NEVER raises. Prompt text and
raw user ids are never stored: `actor` is already a short hash. Empty AI_OPS_DATABASE_URL = no-op (logged once).
"""
import logging
import threading
import time
from typing import Optional

from sqlalchemy import text

from app.core.config import settings
from app.db.session import make_engine

log = logging.getLogger("ai.usage")

BREAKER_SECONDS = 60
WARN_EVERY_SECONDS = 60

_lock = threading.Lock()
_engine = None
_engine_url = None
_breaker_until = 0.0
_last_warn = 0.0
_noop_logged = False

_INSERT = text(
    "INSERT INTO ai_usage (route, tier, provider, model, ok, error_code, latency_ms, input_tokens, output_tokens, "
    "estimated, actor, attempts) VALUES (:route, :tier, :provider, :model, :ok, :error_code, :latency_ms, "
    ":input_tokens, :output_tokens, :estimated, :actor, :attempts)"
)


def _get_engine(url: str):
    global _engine, _engine_url
    with _lock:
        if _engine is None or _engine_url != url:
            _engine = make_engine(url, connect_args={"connect_timeout": 2})
            _engine_url = url
        return _engine


def _warn(msg: str):
    global _last_warn
    now = time.time()
    if now - _last_warn >= WARN_EVERY_SECONDS:
        _last_warn = now
        log.warning(msg)


def record(*, route: str, tier: str, provider: str, model: str, ok: bool, error_code: Optional[str],
           latency_ms: int, input_tokens: Optional[int], output_tokens: Optional[int], estimated: bool,
           actor: Optional[str], attempts: int) -> None:
    global _breaker_until, _noop_logged
    try:
        url = settings.ai_ops_database_url
        if not url:
            if not _noop_logged:
                _noop_logged = True
                log.warning("AI_OPS_DATABASE_URL is empty: model usage is not metered")
            return
        if time.time() < _breaker_until:
            return
        try:
            engine = _get_engine(url)
            with engine.begin() as conn:
                conn.execute(_INSERT, {
                    "route": route, "tier": tier, "provider": provider, "model": model, "ok": bool(ok),
                    "error_code": error_code, "latency_ms": int(latency_ms), "input_tokens": input_tokens,
                    "output_tokens": output_tokens, "estimated": bool(estimated), "actor": actor, "attempts": int(attempts),
                })
        except Exception as failure:
            _breaker_until = time.time() + BREAKER_SECONDS
            _warn(f"ai_usage insert failed ({type(failure).__name__}); skipping for {BREAKER_SECONDS}s")
    except Exception:  # absolutely never propagate
        pass


def reset_for_tests() -> None:
    global _engine, _engine_url, _breaker_until, _last_warn, _noop_logged
    with _lock:
        if _engine is not None:
            try:
                _engine.dispose()
            except Exception:
                pass
        _engine = _engine_url = None
    _breaker_until = 0.0
    _last_warn = 0.0
    _noop_logged = False
