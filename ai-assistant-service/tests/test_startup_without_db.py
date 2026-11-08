import importlib.util

import pytest
from fastapi.testclient import TestClient

from app.core.config import settings
from app.core.errors import AiError
from app.db import engines


def test_session_imports_without_database_url(monkeypatch):
    monkeypatch.delenv("DATABASE_URL", raising=False)
    import app.db.session as original
    spec = importlib.util.spec_from_file_location("session_fresh_copy", original.__file__)
    fresh = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fresh)  # a separate copy, so the shared module is left alone
    assert fresh.engine is None
    for name in ("engine", "SessionLocal", "get_db", "make_engine", "Base"):
        assert hasattr(fresh, name)
    with pytest.raises(AiError) as e:
        next(fresh.get_db())
    assert e.value.code == "AI_DB_NOT_CONFIGURED" and e.value.status == 503


def test_make_engine_builds_without_connecting():
    from app.db.session import make_engine
    eng = make_engine("postgresql+psycopg2://u:p@localhost:1/x")
    assert eng.dialect.name == "postgresql"
    eng.dispose()


def test_chatbot_engines_not_configured(monkeypatch):
    engines.dispose_all()
    monkeypatch.setattr(settings, "chatbot_event_database_url", "")
    monkeypatch.setattr(settings, "chatbot_ticket_database_url", "")
    for kind in ("event", "ticket"):
        with pytest.raises(AiError) as e:
            engines.get_chatbot_engine(kind)
        assert e.value.code == "AI_DB_NOT_CONFIGURED"
    with pytest.raises(ValueError):
        engines.get_chatbot_engine("other")


def test_chatbot_engine_lazy_and_cached(monkeypatch):
    engines.dispose_all()
    monkeypatch.setattr(settings, "chatbot_event_database_url", "postgresql+psycopg2://u:p@localhost:1/event_db")
    a = engines.get_chatbot_engine("event")
    assert engines.get_chatbot_engine("event") is a
    engines.dispose_all()


def test_app_starts_and_health_with_db_vars_unset(monkeypatch):
    for var in ("DATABASE_URL", "EVENT_DB_URL", "TICKET_DB_URL", "BOOKING_DB_URL"):
        monkeypatch.delenv(var, raising=False)
    import main

    async def fake_init(**kw):
        raise RuntimeError("no network in tests")

    async def fake_stop():
        return None

    monkeypatch.setattr(main.eureka_client, "init_async", fake_init)
    monkeypatch.setattr(main.eureka_client, "stop_async", fake_stop)
    with TestClient(main.app) as client:
        r = client.get("/health")
        assert r.status_code == 200 and r.json()["status"] == "UP"
