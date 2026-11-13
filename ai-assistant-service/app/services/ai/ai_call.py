"""Bounded, metered model calls for the routes in scope (agent-chat keeps using llm_factory untouched).

- at most 2 providers per call, tried one after the other (Groq, then OpenAI, then Gemini; first two configured)
- a hard total deadline per call (bounded thread pool + future.result(timeout)); a thread that outlives its deadline
  cannot be killed, so each client also gets its own timeout slightly below the deadline
- max_tokens from the profile; models are built lazily and cached (nothing happens at import)
- every attempt is metered through usage.record (never raises) and kept in an in-memory ring for provider_status()
"""
import logging
import threading
import time
from collections import deque
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

from langchain_google_genai import ChatGoogleGenerativeAI
from langchain_groq import ChatGroq
from langchain_openai import ChatOpenAI

from app.core.config import settings
from app.core.errors import ai_error, AI_UNAVAILABLE, AI_GENERATION_FAILED
from app.core import ratelimit
from app.services.ai import usage

log = logging.getLogger("ai.call")

_PLACEHOLDER_KEY = "your-default-key-if-missing"  # default of settings.gemini_api_key, not a real key
PROVIDERS = ("groq", "openai", "gemini")
MAX_PROVIDERS_PER_CALL = 2
_RING_WINDOW_SECONDS = 24 * 3600

# profiles that count against the global daily budget (customer-facing chat)
BUDGETED_PROFILES = frozenset({"customer_chat"})


@dataclass(frozen=True)
class AiProfile:
    name: str
    tier: str  # 'fast' | 'smart'
    max_tokens: int
    deadline_s: int


def _build_profiles() -> dict:
    d = settings.ai_llm_deadline_seconds
    return {
        "customer_chat": AiProfile("customer_chat", "fast", settings.ai_max_tokens_customer_chat, d),
        "admin_assistant": AiProfile("admin_assistant", "fast", settings.ai_max_tokens_admin_assistant, d),
        "risk_scoring": AiProfile("risk_scoring", "fast", settings.ai_max_tokens_risk_scoring, d),
        "event_content": AiProfile("event_content", "smart", settings.ai_max_tokens_event_content, d),
        "analysis": AiProfile("analysis", "fast", settings.ai_max_tokens_analysis, d),
        "voucher_strategy": AiProfile("voucher_strategy", "fast", settings.ai_max_tokens_voucher_strategy, d),
        "dashboard_summary": AiProfile("dashboard_summary", "fast", settings.ai_max_tokens_dashboard_summary, d),
        "ai_probe": AiProfile("ai_probe", "fast", 1, d),
    }


PROFILES: dict = _build_profiles()

# plumbing
_executor = None
_executor_lock = threading.Lock()
_models: dict = {}
_models_lock = threading.Lock()
_ring: dict = {name: deque(maxlen=500) for name in PROVIDERS}  # (ts, ok, error_code)
_ring_lock = threading.Lock()


def _get_executor() -> ThreadPoolExecutor:
    global _executor
    with _executor_lock:
        if _executor is None:
            _executor = ThreadPoolExecutor(max_workers=8, thread_name_prefix="ai-call")
        return _executor


def _split_keys(value: str) -> list:
    return [k.strip() for k in (value or "").split(",") if k.strip()]


def _candidates(tier: str) -> list:
    """Ordered (provider, model, temperature, key) for the tier; the first MAX_PROVIDERS_PER_CALL are used."""
    smart = tier == "smart"
    temp = 0.0 if smart else 0.2
    out = []
    groq = _split_keys(settings.groq_api_key)
    if groq:
        out.append(("groq", "openai/gpt-oss-120b" if smart else "openai/gpt-oss-20b", temp, groq[0]))
    if settings.openai_api_key:
        out.append(("openai", "gpt-4o-mini", temp, settings.openai_api_key))
    gemini = [k for k in _split_keys(settings.gemini_api_key) if k != _PLACEHOLDER_KEY]
    if gemini:
        out.append(("gemini", "gemini-3.5-flash", temp, gemini[0]))
    return _healthy_first(out)[:MAX_PROVIDERS_PER_CALL]


RECENT_FAILURE_SECONDS = 120


def _healthy_first(candidates: list) -> list:
    """A provider whose last two outcomes were both failures within the last two minutes (quota exhausted, rate limited, down) goes
    to the end, so the next one is tried instead of calling it again. Order is otherwise unchanged; nothing is ever dropped."""
    now = time.time()

    def failing(provider: str) -> bool:
        with _ring_lock:
            last = list(_ring[provider])[-2:]
        return len(last) == 2 and all(not ok and now - ts < RECENT_FAILURE_SECONDS for ts, ok, _code in last)

    return sorted(candidates, key=lambda c: failing(c[0]))  # sorted is stable: False (healthy) first


def _model_for(provider: str, model: str, temp: float, key: str, max_tokens: int, timeout: float):
    cache_key = (provider, model, temp, hash(key), max_tokens, timeout)
    with _models_lock:
        cached = _models.get(cache_key)
        if cached is not None:
            return cached
        if provider == "groq":
            extra = {"reasoning_effort": "low"} if "gpt-oss" in model else {}
            # a reasoning model spends max_tokens on its thinking first: at the default effort a short answer can come back empty
            built = ChatGroq(api_key=key, model=model, temperature=temp, timeout=timeout, max_retries=1, max_tokens=max_tokens, **extra)
        elif provider == "openai":
            built = ChatOpenAI(api_key=key, model=model, temperature=temp, timeout=timeout, max_retries=1, max_tokens=max_tokens)
        else:
            built = ChatGoogleGenerativeAI(api_key=key, model=model, temperature=temp, timeout=timeout, max_retries=1,
                                           max_output_tokens=max_tokens)
        _models[cache_key] = built
        return built


def _content_text(message) -> str:
    content = getattr(message, "content", message)
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for part in content:
            if isinstance(part, str):
                parts.append(part)
            elif isinstance(part, dict) and isinstance(part.get("text"), str):
                parts.append(part["text"])
        return "".join(parts)
    return "" if content is None else str(content)


def _is_empty(message) -> bool:
    if getattr(message, "tool_calls", None):
        return False
    return not _content_text(message).strip()


def _chars_of(messages) -> int:
    if isinstance(messages, str):
        return len(messages)
    total = 0
    try:
        for m in messages:
            total += len(_content_text(m)) if not isinstance(m, tuple) else len(str(m[1]))
    except Exception:
        pass
    return total


def _tokens(response, messages):
    meta = getattr(response, "usage_metadata", None)
    if isinstance(meta, dict) and (meta.get("input_tokens") is not None or meta.get("output_tokens") is not None):
        return meta.get("input_tokens"), meta.get("output_tokens"), False
    return max(1, _chars_of(messages) // 4), max(0, len(_content_text(response)) // 4), True


def _note(provider: str, ok: bool, error_code: Optional[str]) -> None:
    with _ring_lock:
        _ring[provider].append((time.time(), ok, error_code))


def _code_of(failure: BaseException) -> str:
    if isinstance(failure, FutureTimeout):
        return "TIMEOUT"
    return type(failure).__name__[:60]


# public API
def ai_invoke(profile: str, messages, *, actor: Optional[str] = None, tools: Optional[list] = None,
              tier: Optional[str] = None):
    """One bounded model call. Returns the LangChain AIMessage; raises AiError (AI_UNAVAILABLE / AI_GENERATION_FAILED /
    AI_BUDGET_EXHAUSTED). `messages` is whatever the chat models accept (list of messages or a string)."""
    prof = PROFILES.get(profile)
    if prof is None:
        raise ValueError(f"unknown AI profile: {profile}")
    use_tier = tier or prof.tier
    if profile in BUDGETED_PROFILES:
        ratelimit.consume_budget()

    candidates = _candidates(use_tier)
    if not candidates:
        raise ai_error(AI_UNAVAILABLE)

    started = time.monotonic()
    deadline = started + prof.deadline_s
    client_timeout = max(1.0, prof.deadline_s * 0.9)
    last_empty = False
    attempts = 0
    for index, (provider, model_name, temp, key) in enumerate(candidates):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        attempts += 1
        share = remaining / (len(candidates) - index)  # the second provider keeps its share of the budget
        t0 = time.monotonic()
        error_code = None
        response = None
        try:
            model = _model_for(provider, model_name, temp, key, prof.max_tokens, client_timeout)
            if tools:
                model = model.bind_tools(tools)
            future = _get_executor().submit(model.invoke, messages)
            response = future.result(timeout=share)
            if _is_empty(response):
                error_code = "EMPTY_RESPONSE"
        except BaseException as failure:  # includes the future timeout; never leaks to the caller as-is
            if isinstance(failure, (KeyboardInterrupt, SystemExit)):
                raise
            error_code = _code_of(failure)
            log.warning("AI attempt failed (profile=%s provider=%s): %s", profile, provider, error_code)
        latency_ms = int((time.monotonic() - t0) * 1000)
        ok = error_code is None
        if response is not None:
            in_tok, out_tok, estimated = _tokens(response, messages)
        else:
            in_tok, out_tok, estimated = max(1, _chars_of(messages) // 4), 0, True
        _note(provider, ok, error_code)
        usage.record(route=profile, tier=use_tier, provider=provider, model=model_name, ok=ok, error_code=error_code,
                     latency_ms=latency_ms, input_tokens=in_tok, output_tokens=out_tok, estimated=estimated,
                     actor=actor, attempts=attempts)
        if ok:
            return response
        last_empty = error_code == "EMPTY_RESPONSE"
    raise ai_error(AI_GENERATION_FAILED if last_empty else AI_UNAVAILABLE)


class _MeteredLlm:
    def __init__(self, profile: str, actor: Optional[str]):
        self.profile = profile
        self.actor = actor

    def invoke(self, messages, **kwargs):
        return ai_invoke(self.profile, messages, actor=self.actor, tools=kwargs.get("tools"))


def metered_llm(profile: str, actor: Optional[str] = None) -> _MeteredLlm:
    """Object with .invoke(messages, **kwargs) that goes through ai_invoke (for injection into SQLEngine)."""
    return _MeteredLlm(profile, actor)


def provider_status() -> list:
    """Passive status per provider from in-memory outcomes of the last 24 h. No network, no keys in the result."""
    configured = {
        "groq": bool(_split_keys(settings.groq_api_key)),
        "openai": bool(settings.openai_api_key),
        "gemini": bool([k for k in _split_keys(settings.gemini_api_key) if k != _PLACEHOLDER_KEY]),
    }
    cutoff = time.time() - _RING_WINDOW_SECONDS
    result = []
    for name in PROVIDERS:
        with _ring_lock:
            events = [e for e in _ring[name] if e[0] >= cutoff]
        ok_events = [e for e in events if e[1]]
        fail_events = [e for e in events if not e[1]]
        if not events:
            status = "unknown"
        else:
            recent = [e[1] for e in events[-5:]]
            status = "ok" if all(recent) else ("down" if not any(recent) else "degraded")
        result.append({
            "name": name,
            "configured": configured[name],
            "status": status,
            "last_ok_at": datetime.fromtimestamp(ok_events[-1][0], timezone.utc).isoformat() if ok_events else None,
            "last_error_code": fail_events[-1][2] if fail_events else None,
            "ok_count": len(ok_events),
            "fail_count": len(fail_events),
        })
    return result


def reset_for_tests() -> None:
    global _executor
    with _models_lock:
        _models.clear()
    with _ring_lock:
        for d in _ring.values():
            d.clear()
    with _executor_lock:
        if _executor is not None:
            _executor.shutdown(wait=False)
            _executor = None
