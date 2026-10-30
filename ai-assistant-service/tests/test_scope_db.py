"""Phase 2 against the dev database (SELECT only): who owns which events, and that a scoped query cannot read beyond them.
Runs with RUN_DB_TESTS=1 and DATABASE_URL set. Uses the real staff_assignments / events rows and the general dev account
with base tables standing in for the views (the views are created by hand, see postgres/ai-readonly)."""
import os

import pytest
from sqlalchemy import text

from sqlalchemy.engine import make_url

from app.core.config import settings
from app.db.session import SessionLocal
from app.services.ai import scope as scope_module
from app.services.ai.scope import AnalysisScope
from app.services.ai.sql_engine import SQLEngine
from app.services.ai.sql_guard import GuardedSql, validate_sql

pytestmark = [
    pytest.mark.db,
    pytest.mark.skipif(os.getenv("RUN_DB_TESTS") != "1", reason="set RUN_DB_TESTS=1 and DATABASE_URL"),
]


def rows(sql, **params):
    with SessionLocal() as db:
        return db.execute(text(sql), params).fetchall()


def _use_the_dev_account(monkeypatch):
    """The analysis account is required now; the dev database has no read-only role, so these tests use the account in DATABASE_URL."""
    url = make_url(settings.database_url)
    monkeypatch.setattr(settings, "ai_db_user", url.username or "")
    monkeypatch.setattr(settings, "ai_db_password", url.password or "")


@pytest.fixture(scope="module")
def people():
    owner = rows("SELECT user_id FROM staff_assignments WHERE role = 'OWNER' AND status = 'ACTIVE' LIMIT 1")
    if not owner:
        pytest.skip("no OWNER assignment in the dev data")
    checkin = rows("SELECT user_id FROM staff_assignments WHERE role = 'CHECKIN_STAFF' AND status = 'ACTIVE' "
                   "AND user_id NOT IN (SELECT user_id FROM staff_assignments WHERE role IN ('OWNER','ADMIN','MANAGER'))")
    return {"owner": owner[0][0], "checkin": checkin[0][0] if checkin else None}


@pytest.fixture(autouse=True)
def fresh_cache():
    scope_module.clear_cache()


@pytest.fixture
def engine(monkeypatch):
    _use_the_dev_account(monkeypatch)
    monkeypatch.setattr(settings, "ai_analysis_schema", "public")
    monkeypatch.setattr(settings, "ai_sql_allowed_relations", "events,event_showtimes,venues")
    monkeypatch.setattr(settings, "ai_scope_columns", "events:id,event_showtimes:event_id")
    monkeypatch.setattr(settings, "ai_scope_public_relations", "venues")
    return SQLEngine()


def ask(engine, sql, scope):
    guarded = validate_sql(sql, engine.allowed_relations, schema=engine.schema, max_rows=500, scope=scope.spec())
    return engine.run_readonly(guarded, scope.session_value)


def all_event_ids():
    return {row[0] for row in rows("SELECT id::text FROM events")}


# --- which events a person manages ---------------------------------------------------------------------------------------

def test_an_owner_manages_the_events_where_they_are_an_active_owner(people):
    expected = {r[0] for r in rows("SELECT event_id::text FROM staff_assignments WHERE user_id = :u AND status = 'ACTIVE' "
                                  "AND role IN ('OWNER','ADMIN','MANAGER')", u=people["owner"])}
    got = set(scope_module.events_for_user(people["owner"]))
    assert got >= expected and got  # plus events reached only through the organizer profile, never fewer


def test_check_in_staff_manage_nothing(people):
    if not people["checkin"]:
        pytest.skip("no check-in-only member in the dev data")
    assert scope_module.events_for_user(people["checkin"]) == []


def test_a_stranger_manages_nothing():
    assert scope_module.events_for_user("00000000-0000-0000-0000-000000000000") == []


# --- what a scoped query can read ------------------------------------------------------------------------------------------

def test_organizer_a_reads_only_their_events_and_never_organizer_bs(engine, people):
    mine = set(scope_module.events_for_user(people["owner"]))
    others = all_event_ids() - mine
    if not others:
        pytest.skip("every dev event belongs to the one owner")
    a = AnalysisScope(False, tuple(sorted(mine)))
    b = AnalysisScope(False, tuple(sorted(others)))
    seen_by_a = {r["id"] for r in ask(engine, "SELECT id FROM events", a)}
    seen_by_b = {r["id"] for r in ask(engine, "SELECT id FROM events", b)}
    assert seen_by_a == mine and seen_by_b == others
    assert seen_by_a.isdisjoint(seen_by_b)


def test_the_same_holds_through_a_join_a_subquery_and_a_union(engine, people):
    mine = set(scope_module.events_for_user(people["owner"]))
    others = all_event_ids() - mine
    if not others:
        pytest.skip("every dev event belongs to the one owner")
    a = AnalysisScope(False, tuple(sorted(mine)))
    queries = [
        "SELECT e.id FROM events e JOIN event_showtimes s ON s.event_id = e.id",
        "SELECT id FROM events WHERE id IN (SELECT event_id FROM event_showtimes)",
        "SELECT id FROM events UNION SELECT event_id FROM event_showtimes",
        "WITH x AS (SELECT id FROM events) SELECT id FROM x",
        "SELECT event_id AS id FROM event_showtimes",
    ]
    for query in queries:
        ids = {r["id"] for r in ask(engine, query, a)}
        assert ids <= mine, query


def test_an_organizer_with_no_events_reads_nothing(engine):
    nobody = AnalysisScope(False, ())
    assert ask(engine, "SELECT count(*) AS n FROM events", nobody) == [{"n": 0}]
    assert ask(engine, "SELECT id FROM events", nobody) == []
    assert ask(engine, "SELECT count(*) AS n FROM event_showtimes", nobody) == [{"n": 0}]


def test_an_admin_reads_every_event(engine):
    total = len(all_event_ids())
    assert ask(engine, "SELECT count(*) AS n FROM events", AnalysisScope.admin()) == [{"n": total}]


def test_public_relations_are_readable_by_an_organizer(engine):
    count = rows("SELECT count(*) FROM venues")[0][0]
    assert ask(engine, "SELECT count(*) AS n FROM venues", AnalysisScope(False, ()))[0]["n"] == count


def test_the_session_setting_for_the_views_carries_the_scope(engine):
    probe = GuardedSql("SELECT current_setting('app.allowed_event_ids') AS ids", 1)
    assert engine.run_readonly(probe, "evt-a,evt-b") == [{"ids": "evt-a,evt-b"}]
    assert engine.run_readonly(probe) == [{"ids": "*"}]
    assert engine.run_readonly(probe, "") == [{"ids": ""}]  # an organizer with nothing: empty, never "*"
