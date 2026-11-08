"""The real SQL of app.services.admin_intel.store against a throwaway Postgres where ai-admin/01-ai-admin-schema.sql was run.
Skipped unless RUN_ADMIN_STORE_TESTS=1 and ADMIN_STORE_DB_URL is the URL of role ai_admin_rw, e.g.
    RUN_ADMIN_STORE_TESTS=1 ADMIN_STORE_DB_URL=postgresql+psycopg2://ai_admin_rw:pw@localhost:55432/ai_ops pytest tests/test_admin_store_db.py
Never point it at a shared database: it writes rows."""
import os

import pytest
from sqlalchemy import text
from sqlalchemy.exc import ProgrammingError

from app.core.config import settings
from app.services.admin_intel import store

pytestmark = [pytest.mark.db, pytest.mark.skipif(os.getenv("RUN_ADMIN_STORE_TESTS") != "1", reason="needs a throwaway database")]

RESULT = {"risk_score": 55, "risk_level": "MEDIUM", "complete": False, "ruleset_version": "1", "reason_items": [{"layer": "RULE", "code": "contact_phone", "weight": 25, "message": "m", "evidence": []}],
          "coverage": {"rules": True, "signals": {"assessed": 6, "total": 6, "missing": []}, "ai": "skipped"}}


@pytest.fixture(autouse=True)
def database(monkeypatch):
    monkeypatch.setattr(settings, "ai_admin_database_url", os.environ["ADMIN_STORE_DB_URL"])
    store.reset_for_tests()
    yield
    store.reset_for_tests()


def test_assessments_round_trip_and_the_latest_wins():
    first = store.save_assessment("ev-db-1", RESULT, "hash1", "queue", "sub:a")
    second = store.save_assessment("ev-db-1", {**RESULT, "risk_score": 80, "risk_level": "HIGH"}, "hash2", "manual", "sub:a")
    latest = store.latest_assessment("ev-db-1")
    assert latest["assessment_id"] == second > first and latest["risk_level"] == "HIGH" and latest["content_hash"] == "hash2"
    assert latest["reason_items"][0]["code"] == "contact_phone" and latest["coverage"]["ai"] == "skipped" and latest["ai_layer"] == "skipped"
    assert [h["risk_score"] for h in store.assessment_history("ev-db-1", 5)] == [80, 55]
    assert set(store.latest_assessments(["ev-db-1", "nope"])) == {"ev-db-1"} and store.latest_assessments([]) == {}
    assert store.latest_assessment("missing") is None
    assert {a["event_id"] for a in store.assessed_since(1)} >= {"ev-db-1"}


def test_decisions_dedupe_and_keep_the_latest_per_event():
    store.save_assessment("ev-db-2", RESULT, "h", "queue", "sub:a")
    first = store.save_decision("ev-db-2", "REJECTED", "lý do", "admin-1", "web")
    again = store.save_decision("ev-db-2", "REJECTED", "lý do", "admin-1", "web")
    assert first["duplicate"] is False and again["duplicate"] is True
    assert {"event_id": "ev-db-2", "decision": "REJECTED", "source": "web"} in store.decisions_since(1)


def test_pins_belong_to_one_admin():
    pin = store.create_pin("admin-db-1", "Tiêu đề", "Câu hỏi?", "SELECT 1", "bar", [{"a": 1}])
    assert store.count_pins("admin-db-1") >= 1 and store.get_pin("admin-db-2", pin) is None
    store.update_pin_snapshot("admin-db-1", pin, [{"a": 2}])
    assert store.get_pin("admin-db-1", pin)["snapshot"] == [{"a": 2}]
    assert store.delete_pin("admin-db-2", pin) is False and store.delete_pin("admin-db-1", pin) is True


def test_summaries_upsert_per_day_and_language():
    store.put_summary("2026-11-08", "vi", "Bản một", "k1")
    store.put_summary("2026-11-08", "vi", "Bản hai", "k2")
    assert store.get_summary("2026-11-08", "vi")["summary"] == "Bản hai" and store.get_summary("2026-11-08", "en") is None


def test_the_role_can_only_do_what_the_sql_file_grants():
    with pytest.raises(ProgrammingError):
        with store.require_engine().begin() as conn:
            conn.execute(text("UPDATE event_risk_assessments SET score = 0"))  # append only: no UPDATE
    with pytest.raises(ProgrammingError):
        with store.require_engine().begin() as conn:
            conn.execute(text("CREATE TABLE should_not_exist (id int)"))
    with pytest.raises(ProgrammingError):
        with store.require_engine().begin() as conn:
            conn.execute(text("DELETE FROM event_risk_decisions"))
