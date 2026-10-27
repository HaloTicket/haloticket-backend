"""The execution side of the analysis engine against the dev database (SELECT only). Runs with RUN_DB_TESTS=1 and
DATABASE_URL set, like test_database_usage.py. Uses the general dev account with base tables as the allow-list, because
the read-only role and the views are created by hand (postgres/ai-readonly) and are not part of the dev database."""
import os

import pytest
from sqlalchemy.exc import DBAPIError

from app.core.config import settings
from app.services.ai.sql_engine import SQLEngine
from app.services.ai.sql_guard import GuardedSql, validate_sql

pytestmark = [
    pytest.mark.db,
    pytest.mark.skipif(os.getenv("RUN_DB_TESTS") != "1", reason="set RUN_DB_TESTS=1 and DATABASE_URL"),
]


@pytest.fixture
def engine(monkeypatch):
    monkeypatch.setattr(settings, "ai_db_user", "")
    monkeypatch.setattr(settings, "ai_analysis_schema", "public")
    monkeypatch.setattr(settings, "ai_sql_allowed_relations", "events,event_showtimes,venues")
    monkeypatch.setattr(settings, "ai_sql_timeout_ms", 1500)
    return SQLEngine()


def run(engine, sql, max_rows=500):
    return engine.run_readonly(validate_sql(sql, engine.allowed_relations, schema=engine.schema, max_rows=max_rows))


def test_a_checked_query_runs_and_returns_rows_as_dicts(engine):
    rows = run(engine, "SELECT count(*) AS n FROM events")
    assert list(rows[0].keys()) == ["n"] and rows[0]["n"] >= 0


def test_the_rows_are_capped_at_the_limit(engine):
    assert len(run(engine, "SELECT id FROM event_showtimes", max_rows=3)) <= 3


def test_text_with_colons_and_percent_signs_is_not_taken_for_parameters(engine):
    rows = run(engine, "SELECT 'a:b' AS colon, 'c%d' AS percent, 7::int AS seven FROM events LIMIT 1")
    assert rows == [{"colon": "a:b", "percent": "c%d", "seven": 7}]


def test_the_session_is_read_only(engine):
    rows = engine.run_readonly(GuardedSql("SELECT current_setting('transaction_read_only') AS ro", 1))
    assert rows == [{"ro": "on"}]


def test_a_slow_query_is_cancelled_by_the_statement_timeout(engine):
    with pytest.raises(DBAPIError):
        engine.run_readonly(GuardedSql("SELECT pg_sleep(10)", 1))


def test_the_connection_goes_back_to_the_pool_after_every_run(engine):
    for _ in range(10):
        run(engine, "SELECT count(*) AS n FROM events")
    assert engine.engine.pool.checkedout() == 0


def test_a_failing_query_still_returns_its_connection(engine):
    with pytest.raises(DBAPIError):
        engine.run_readonly(GuardedSql("SELECT * FROM table_that_does_not_exist", 1))
    assert engine.engine.pool.checkedout() == 0


def test_a_result_larger_than_the_character_cap_is_cut(engine, monkeypatch):
    monkeypatch.setattr(settings, "ai_sql_max_result_chars", 300)
    rows = run(engine, "SELECT title, description FROM events", max_rows=100)
    assert sum(len(str(row)) for row in rows) < 1000
