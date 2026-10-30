"""Phase 2: an organizer's analysis is limited to the events they manage. No database needed."""
import time
from types import SimpleNamespace

import jwt
import sqlglot
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlglot import exp

from app.api.endpoints import organizer
from app.core.config import settings
from app.core.security import TokenVerifier, get_verifier
from app.services.ai import scope as scope_module
from app.services.ai.scope import AnalysisScope, ScopeUnavailable, analysis_scope
from app.services.ai.sql_guard import ScopeSpec, SqlGuardError, validate_sql

ISSUER = "https://login.example.test/realms/haloticket"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
ALLOWED = ["v_events", "v_ticket_types", "v_ticket_sales_daily", "v_venues", "v_search_terms"]
SPEC = ScopeSpec(
    columns={"v_events": "event_id", "v_ticket_types": "event_id", "v_ticket_sales_daily": "event_id"},
    public=frozenset({"v_venues"}),
    event_ids=("evt-a", "evt-b"),
)
EMPTY = ScopeSpec(SPEC.columns, SPEC.public, ())


def scoped(sql, spec=SPEC, **kwargs):
    return validate_sql(sql, kwargs.pop("allowed", ALLOWED), schema=None, max_rows=500, scope=spec).sql


def refused(sql, spec=SPEC):
    with pytest.raises(SqlGuardError) as refusal:
        scoped(sql, spec)
    return refusal.value.code


# --- the rewrite ----------------------------------------------------------------------------------------------------

def test_a_simple_query_is_limited_to_the_events_in_scope():
    sql = scoped("SELECT count(*) FROM v_events")
    assert "event_id IN ('evt-a', 'evt-b')" in sql
    assert sql.startswith("SELECT COUNT(*) FROM (SELECT * FROM v_events WHERE event_id IN")


def test_an_alias_survives_the_rewrite():
    sql = scoped("SELECT e.title FROM v_events AS e WHERE e.city = 'Hà Nội'")
    assert "AS e" in sql and "e.title" in sql and "evt-a" in sql


def test_a_join_limits_both_sides():
    sql = scoped("SELECT e.title, sum(s.revenue) FROM v_events e JOIN v_ticket_sales_daily s ON s.event_id = e.event_id GROUP BY e.title")
    assert sql.count("event_id IN ('evt-a', 'evt-b')") == 2


def test_every_use_of_a_relation_is_limited_wherever_it_hides():
    for query in [
        "SELECT title FROM v_events WHERE event_id IN (SELECT event_id FROM v_ticket_types WHERE price < 100)",
        "SELECT (SELECT count(*) FROM v_events) AS n FROM v_venues",
        "WITH recent AS (SELECT event_id FROM v_events) SELECT count(*) FROM recent",
        "SELECT title FROM v_events UNION ALL SELECT name FROM v_ticket_types",
        "SELECT * FROM (SELECT * FROM (SELECT * FROM v_events) a) b",
        "SELECT title FROM v_events WHERE EXISTS (SELECT 1 FROM v_events x WHERE x.event_id = v_events.event_id)",
    ]:
        sql = scoped(query)
        uses = sum(1 for table in sqlglot.parse_one(query, read="postgres").find_all(exp.Table) if table.name.lower() in SPEC.columns)
        assert sql.count("event_id IN ('evt-a', 'evt-b')") == uses, query


def test_public_relations_are_read_as_they_are():
    sql = scoped("SELECT name, capacity FROM v_venues")
    assert "evt-a" not in sql and "v_venues" in sql


def test_no_events_in_scope_gives_a_query_that_returns_nothing():
    sql = scoped("SELECT count(*) FROM v_events", EMPTY)
    assert "WHERE FALSE" in sql.upper()
    assert "evt-" not in sql


def test_event_ids_are_written_as_escaped_literals():
    tricky = ScopeSpec(SPEC.columns, SPEC.public, ("x'); DROP TABLE v_events; --",))
    sql = scoped("SELECT count(*) FROM v_events", tricky)
    assert "x''); DROP TABLE v_events; --" in sql  # the quote is doubled: it stays inside the string literal
    assert validate_sql(sql, ALLOWED, schema=None, max_rows=500, scope=None).sql  # and the result is still one plain SELECT


# --- what an organizer is not given -----------------------------------------------------------------------------------

def test_a_relation_that_cannot_be_limited_is_refused_for_an_organizer():
    assert refused("SELECT * FROM v_search_terms") == "RELATION_NOT_ALLOWED"


def test_an_admin_query_is_not_rewritten():
    sql = validate_sql("SELECT count(*) FROM v_events", ALLOWED, schema=None, max_rows=500, scope=None).sql
    assert "evt-a" not in sql


def test_a_cte_cannot_borrow_the_name_of_a_scoped_view_to_escape_the_filter():
    assert refused("WITH v_events AS (SELECT * FROM v_events) SELECT * FROM v_events") == "RELATION_NOT_ALLOWED"
    assert refused("WITH v_ticket_types AS (SELECT 1 AS event_id) SELECT * FROM v_ticket_types") == "RELATION_NOT_ALLOWED"


def test_the_organizer_allow_list_is_never_wider_than_the_configured_one():
    narrow = validate_sql("SELECT 1 FROM v_events", ["v_events"], schema=None, scope=SPEC).sql
    assert "evt-a" in narrow
    with pytest.raises(SqlGuardError):
        validate_sql("SELECT 1 FROM v_ticket_types", ["v_events"], schema=None, scope=SPEC)


@pytest.mark.parametrize("sql,code", [
    ("SELECT * FROM v_events; SELECT * FROM v_events", "MULTIPLE_STATEMENTS"),
    ("SELECT pg_read_file('x') FROM v_events", "FUNCTION_NOT_ALLOWED"),
    ("SELECT * FROM v_events -- all", "COMMENT_NOT_ALLOWED"),
    ("DELETE FROM v_events", "NOT_A_SELECT"),
])
def test_the_ordinary_checks_still_apply_to_an_organizer(sql, code):
    assert refused(sql) == code


# --- who gets which scope -------------------------------------------------------------------------------------------

def claims(roles):
    return {"sub": "kc-" + "-".join(roles), "roles": roles}


@pytest.fixture(autouse=True)
def fresh_cache():
    scope_module.clear_cache()
    yield
    scope_module.clear_cache()


def test_an_admin_gets_every_event_without_any_lookup(monkeypatch):
    monkeypatch.setattr(scope_module, "fetch_user_id", lambda token: pytest.fail("an admin needs no lookup"))
    scope = scope_module.resolve_scope(claims(["ROLE_ADMIN"]), "t")
    assert scope.is_admin and scope.spec() is None and scope.session_value == "*"


def test_an_organizer_gets_the_events_the_lookup_returns(monkeypatch):
    monkeypatch.setattr(scope_module, "fetch_user_id", lambda token: "user-1")
    monkeypatch.setattr(scope_module, "events_for_user", lambda user_id: ["evt-a", "evt-b"] if user_id == "user-1" else [])
    scope = scope_module.resolve_scope(claims(["ROLE_USER"]), "t")
    assert not scope.is_admin and scope.event_ids == ("evt-a", "evt-b") and scope.session_value == "evt-a,evt-b"
    assert scope.allows_event("evt-a") and not scope.allows_event("evt-z")


def test_an_organizer_with_no_events_gets_an_empty_scope_not_everything(monkeypatch):
    monkeypatch.setattr(scope_module, "fetch_user_id", lambda token: "user-2")
    monkeypatch.setattr(scope_module, "events_for_user", lambda user_id: [])
    scope = scope_module.resolve_scope(claims(["ROLE_USER"]), "t")
    assert scope.event_ids == () and scope.session_value == "" and scope.spec().event_ids == ()


def test_the_number_of_events_is_capped(monkeypatch):
    monkeypatch.setattr(settings, "ai_scope_max_events", 3)
    monkeypatch.setattr(scope_module, "fetch_user_id", lambda token: "u")
    monkeypatch.setattr(scope_module, "events_for_user", lambda user_id: [f"e{i}" for i in range(10)])
    assert len(scope_module.resolve_scope(claims(["ROLE_USER"]), "t").event_ids) == 3


def test_the_answer_is_cached_for_a_short_time(monkeypatch):
    lookups = []
    monkeypatch.setattr(scope_module, "fetch_user_id", lambda token: lookups.append(1) or "u")
    monkeypatch.setattr(scope_module, "events_for_user", lambda user_id: ["evt-a"])
    for _ in range(3):
        scope_module.resolve_scope(claims(["ROLE_USER"]), "t")
    assert len(lookups) == 1


# --- the routes ---------------------------------------------------------------------------------------------------------

def pem(key):
    return key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())


def token(roles, sub):
    return jwt.encode({"iss": ISSUER, "sub": sub, "roles": roles, "exp": int(time.time()) + 300}, pem(KEY), algorithm="RS256")


@pytest.fixture
def api(monkeypatch):
    seen = []

    def analyze(question, scope=None):
        seen.append(scope)
        return {"question": question, "reply": "ok", "data": []}

    monkeypatch.setattr(organizer, "sql_engine", SimpleNamespace(analyze_data=analyze))
    monkeypatch.setattr(organizer, "organizer_agent", SimpleNamespace(chat=lambda **kw: {"reply": "ok"}, generate_event_content=lambda t, b: {}))
    app = FastAPI()
    app.include_router(organizer.router, prefix="/api/organizer")
    app.dependency_overrides[get_verifier] = lambda: TokenVerifier(ISSUER, lambda _t: KEY.public_key())
    client = TestClient(app)
    client.seen = seen
    return client


def auth(roles, sub="kc-user"):
    return {"Authorization": "Bearer " + token(roles, sub)}


def test_an_admin_sees_all_events(api):
    assert api.post("/api/organizer/data-analysis", json={"question": "q"}, headers=auth(["ROLE_ADMIN"])).status_code == 200
    assert api.seen[0].is_admin
    assert api.seen[0].session_value == "*" and api.seen[0].spec() is None


def test_an_organizer_reaches_data_analysis_with_their_own_scope(api, monkeypatch):
    monkeypatch.setattr(scope_module, "fetch_user_id", lambda t: "user-1")
    monkeypatch.setattr(scope_module, "events_for_user", lambda uid: ["evt-a"])
    assert api.post("/api/organizer/data-analysis", json={"question": "q"}, headers=auth(["ROLE_USER"])).status_code == 200
    assert api.seen[0].event_ids == ("evt-a",) and not api.seen[0].is_admin
    assert api.seen[0].session_value == "evt-a"


def test_an_organizer_only_sees_their_own_events(api, monkeypatch):
    owned = {"user-1": ["evt-a"], "user-2": ["evt-b", "evt-c"]}
    scope_module.clear_cache()
    # the subject of the token stands in for the user-service id
    monkeypatch.setattr(scope_module, "fetch_user_id", lambda t: jwt.decode(t, options={"verify_signature": False})["sub"])
    monkeypatch.setattr(scope_module, "events_for_user", lambda uid: owned[uid])
    api.post("/api/organizer/data-analysis", json={"question": "q"}, headers=auth(["ROLE_USER"], sub="user-1"))
    api.post("/api/organizer/data-analysis", json={"question": "q"}, headers=auth(["ROLE_USER"], sub="user-2"))
    scope_module.clear_cache()
    assert [s.event_ids for s in api.seen] == [("evt-a",), ("evt-b", "evt-c")]
    assert api.seen[0].allows_event("evt-a") and not api.seen[0].allows_event("evt-b")


def test_anonymous_still_gets_401(api):
    assert api.post("/api/organizer/data-analysis", json={"question": "q"}).status_code == 401


def test_voucher_strategy_for_an_event_outside_the_scope_is_refused(api, monkeypatch):
    monkeypatch.setattr(scope_module, "fetch_user_id", lambda t: "user-1")
    monkeypatch.setattr(scope_module, "events_for_user", lambda uid: ["evt-a"])
    inside = api.post("/api/organizer/voucher-strategy", json={"event_id": "evt-a"}, headers=auth(["ROLE_USER"]))
    outside = api.post("/api/organizer/voucher-strategy", json={"event_id": "evt-z"}, headers=auth(["ROLE_USER"]))
    assert inside.status_code == 200
    assert outside.status_code == 403 and outside.json()["detail"]["code"] == "EVENT_NOT_IN_SCOPE"
    assert len(api.seen) == 1  # the refused one never reached the engine


def test_an_admin_may_ask_about_any_event(api):
    assert api.post("/api/organizer/voucher-strategy", json={"event_id": "anything"}, headers=auth(["ROLE_ADMIN"])).status_code == 200


def test_the_organizer_agent_is_still_admin_only(api):
    assert api.post("/api/organizer/agent-chat", json={"message": "hi"}, headers=auth(["ROLE_USER"])).status_code == 403
    assert api.post("/api/organizer/agent-chat", json={"message": "hi"}, headers=auth(["ROLE_ADMIN"])).status_code == 200


def test_when_the_scope_cannot_be_worked_out_nothing_runs(api, monkeypatch):
    def down(token):
        raise ScopeUnavailable()

    monkeypatch.setattr(scope_module, "fetch_user_id", down)
    response = api.post("/api/organizer/data-analysis", json={"question": "q"}, headers=auth(["ROLE_USER"]))
    assert response.status_code == 503 and response.json()["detail"]["code"] == "SCOPE_UNAVAILABLE"
    assert api.seen == []
