"""Lazy, cached engines for the narrow read-only chatbot roles (event_db / ticket_db)."""
import threading

from sqlalchemy.engine import Engine

from app.core.config import settings
from app.core.errors import ai_error, AI_DB_NOT_CONFIGURED
from app.db.session import make_engine

STATEMENT_TIMEOUT_MS = 8000

_engines: dict = {}
_lock = threading.Lock()


def _url_for(kind: str) -> str:
    if kind == "event":
        return settings.chatbot_event_database_url
    if kind == "ticket":
        return settings.chatbot_ticket_database_url
    raise ValueError("kind must be 'event' or 'ticket'")


def get_chatbot_engine(kind: str) -> Engine:
    url = _url_for(kind)
    if not url:
        raise ai_error(AI_DB_NOT_CONFIGURED)
    with _lock:
        cached = _engines.get(kind)
        if cached is not None and cached[0] == url:
            return cached[1]
        if cached is not None:
            cached[1].dispose()
        engine = make_engine(
            url,
            connect_args={"connect_timeout": 5, "options": f"-c statement_timeout={STATEMENT_TIMEOUT_MS}"},
        )
        _engines[kind] = (url, engine)
        return engine


def dispose_all() -> None:
    with _lock:
        for _, engine in _engines.values():
            try:
                engine.dispose()
            except Exception:
                pass
        _engines.clear()
