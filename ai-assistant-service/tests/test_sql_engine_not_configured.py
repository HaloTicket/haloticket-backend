"""The analysis never falls back to the general database account: without AI_DB_USER / AI_DB_PASSWORD it answers 503."""

from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.endpoints import organizer
from app.core.config import settings
from app.core.security import require_login
from app.services.ai import sql_engine as sql_engine_module
from app.services.ai.scope import AnalysisScope, analysis_scope
from app.services.ai.sql_engine import AnalysisDbNotConfigured, SQLEngine


@pytest.fixture
def engine(monkeypatch):
    monkeypatch.setattr(sql_engine_module, "get_fast_llm", lambda: SimpleNamespace(invoke=lambda *a, **k: pytest.fail("the model must not be called")))
    return SQLEngine()


@pytest.mark.parametrize("user,password", [("", ""), ("ai_readonly", ""), ("", "secret")])
def test_analysis_is_refused_when_the_account_is_incomplete(engine, monkeypatch, user, password):
    monkeypatch.setattr(settings, "ai_db_user", user)
    monkeypatch.setattr(settings, "ai_db_password", password)
    with pytest.raises(AnalysisDbNotConfigured) as refusal:
        engine.analyze_data("how many events?", AnalysisScope.admin())
    assert refusal.value.status_code == 503 and refusal.value.detail["code"] == "AI_DB_NOT_CONFIGURED"
    with pytest.raises(AnalysisDbNotConfigured):
        engine.engine  # no connection is built from the general account


def test_the_routes_answer_503_AI_DB_NOT_CONFIGURED_and_the_service_keeps_running(engine, monkeypatch):
    monkeypatch.setattr(settings, "ai_db_user", "")
    monkeypatch.setattr(settings, "ai_db_password", "")
    monkeypatch.setattr(organizer, "sql_engine", engine)
    app = FastAPI()
    app.include_router(organizer.router, prefix="/api/organizer")
    app.dependency_overrides[require_login] = lambda: {"roles": ["ROLE_ADMIN"]}
    app.dependency_overrides[analysis_scope] = lambda: AnalysisScope.admin()
    client = TestClient(app)
    for path, body in [("/api/organizer/data-analysis", {"question": "q"}), ("/api/organizer/voucher-strategy", {"event_id": "e1"})]:
        response = client.post(path, json=body)
        assert response.status_code == 503, path
        assert response.json()["detail"]["code"] == "AI_DB_NOT_CONFIGURED"


def test_with_the_account_set_the_url_uses_it(engine, monkeypatch):
    monkeypatch.setattr(settings, "database_url", "postgresql+psycopg2://haloticket:general@db:5432/event_db")
    monkeypatch.setattr(settings, "ai_db_user", "ai_readonly")
    monkeypatch.setattr(settings, "ai_db_password", "s3cret")
    url = engine._analysis_url()
    assert url.username == "ai_readonly" and url.password == "s3cret" and url.host == "db"
