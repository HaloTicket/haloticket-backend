"""data-analysis and voucher-strategy through the metered model layer.

Each profile ('analysis', 'voucher_strategy') has its own SQLEngine built with `llm=` a metered runnable, so the two
routes use ai_invoke (limits, deadline, usage records). The singleton `sql_engine` of sql_engine.py is NOT touched: the
agent-chat SQL_QUERY path keeps using it. This module only maps the engine's result dict to AiError codes.
"""
import contextvars
import logging
import threading
from typing import Optional

from langchain_core.runnables import RunnableLambda

from app.core.errors import ai_error, AI_QUESTION_REFUSED, AI_UNAVAILABLE
from app.services.ai.ai_call import metered_llm
from app.services.ai.scope import AnalysisScope
from app.services.ai.sql_engine import SQLEngine, sql_engine as LEGACY_ENGINE

log = logging.getLogger("ai.analysis")

PROFILES = ("analysis", "voucher_strategy")
# codes analyze_data puts in result["error"] when the system (not the question) is at fault
SYSTEM_FAILURES = frozenset({"NO_ALLOWED_RELATIONS", "SCHEMA_UNAVAILABLE", "ANALYSIS_FAILED"})

_actor = contextvars.ContextVar("ai_analysis_actor", default=None)
_engines: dict = {}
_lock = threading.Lock()


def _runnable(profile: str):
    # `prompt | llm` needs a Runnable, so the metered object is wrapped; the actor of the request comes from a context var
    def call(prompt_value):
        return metered_llm(profile, _actor.get()).invoke(prompt_value.to_messages())

    return RunnableLambda(call)


def engine_for(profile: str) -> SQLEngine:
    if profile not in PROFILES:
        raise ValueError(f"unknown analysis profile: {profile}")
    with _lock:
        engine = _engines.get(profile)
        if engine is None:
            engine = SQLEngine(llm=_runnable(profile))
            _engines[profile] = engine
        return engine


def reset_for_tests() -> None:
    with _lock:
        _engines.clear()


def analyze(question: str, scope: Optional[AnalysisScope], profile: str = "analysis", actor: Optional[str] = None,
            engine=None) -> dict:
    """Runs the question and returns the engine's success dict. HTTPException (503 AI_DB_NOT_CONFIGURED) passes through.
    Raises AiError AI_QUESTION_REFUSED (guard refusal) or AI_UNAVAILABLE (no relations, schema unreadable, failure)."""
    engine = engine if engine is not None else engine_for(profile)
    token = _actor.set(actor)
    try:
        result = engine.analyze_data(question, scope)
    finally:
        _actor.reset(token)
    return outcome(result)


def outcome(result) -> dict:
    if not isinstance(result, dict):
        raise ai_error(AI_UNAVAILABLE)
    error = result.get("error")
    if not error:
        return result
    if error in SYSTEM_FAILURES:
        log.warning("Analysis not available: %s", error)
        raise ai_error(AI_UNAVAILABLE)
    # any other error text is a refusal of the checked query (sql_guard); its message is not shown to the caller
    raise ai_error(AI_QUESTION_REFUSED)
