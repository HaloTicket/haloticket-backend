"""chat-search and the customer route contract: rate-limit order, cache, honest scores, degradation, errors. No network."""
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy.exc import OperationalError

import customer_fakes as fakes
from app.api.endpoints import customer
from app.core import ratelimit
from app.core.config import settings
from app.core.errors import ai_error, install_error_handlers, AI_GENERATION_FAILED
from app.services.ai import customer_sql, rag_engine as rag_module, search_cache


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    ratelimit.reset_for_tests()
    search_cache.reset_for_tests()
    monkeypatch.setattr(settings, "redis_url", "")
    yield
    ratelimit.reset_for_tests()
    search_cache.reset_for_tests()


def make_client():
    app = FastAPI()
    install_error_handlers(app)
    app.include_router(customer.router, prefix="/api/customer")
    return TestClient(app)


def event_row(eid, title="Show"):
    return fakes.row(id=eid, title=title, description="d", banner_url=None, thumbnail_url="t.png", location_name="Hall",
                     city="HCM", ward="", address="", dates_summary="1/1", min_price=100000)


def vector_hit(eid, score, content="SECRET raw content"):
    return customer_sql.vector_hit(eid, content, score)


def use_engines(monkeypatch, engines):
    monkeypatch.setattr(customer, "get_chatbot_engine", engines)


class CountingRag:
    def __init__(self, hits=None, failure=None):
        self.calls, self.hits, self.failure = 0, hits or [], failure

    def __call__(self, conn, query, top_k=3, **kw):
        self.calls += 1
        if self.failure:
            raise self.failure
        return self.hits


# ------------------------------------------------------------------ rate limit order
def test_empty_body_is_422_and_still_counted_then_429_with_retry_after(monkeypatch):
    monkeypatch.setattr(settings, "ai_rl_customer_search_per_minute", 2)
    use_engines(monkeypatch, fakes.Engines())
    client = make_client()
    assert client.post("/api/customer/chat-search", json={}).status_code == 422
    assert client.post("/api/customer/chat-search", json={}).status_code == 422
    limited = client.post("/api/customer/chat-search", json={"query": "nhạc"})
    assert limited.status_code == 429
    assert limited.json()["code"] == "AI_RATE_LIMITED"
    assert int(limited.headers["Retry-After"]) >= 1
    assert limited.json()["retry_after_seconds"] == int(limited.headers["Retry-After"])


def test_chat_limit_comes_before_body_validation_and_before_the_model(monkeypatch):
    monkeypatch.setattr(settings, "ai_rl_customer_chat_ip_per_minute", 1)
    calls = []
    monkeypatch.setattr(customer.booking_agent, "chat", lambda m, h, db: calls.append(m) or {"reply": "ok", "system_actions": []})
    client = make_client()
    assert client.post("/api/customer/smart-booking-chat", json={}).status_code == 422  # counted (1 of 1)
    limited = client.post("/api/customer/smart-booking-chat", json={"message": "hi"})
    assert limited.status_code == 429 and "Retry-After" in limited.headers
    assert calls == []


# ------------------------------------------------------------------ cache
def test_second_identical_search_is_served_from_cache_without_db_or_embedding(monkeypatch):
    engines = fakes.Engines(event=[("e.id IN", [event_row("ev1")])])
    use_engines(monkeypatch, engines)
    rag = CountingRag([vector_hit("ev1", 0.8)])
    monkeypatch.setattr(customer.rag_engine, "search_events", rag)
    client = make_client()

    first = client.post("/api/customer/chat-search", json={"query": "Nhạc  Trịnh", "top_k": 4})
    statements_after_first = len(engines.statements)
    second = client.post("/api/customer/chat-search", json={"query": " nhạc trịnh ", "top_k": 4})

    assert first.status_code == second.status_code == 200
    assert second.json()["events"] == first.json()["events"]
    assert rag.calls == 1
    assert len(engines.statements) == statements_after_first
    assert len(engines.kinds_requested) == 1


def test_cache_keys_by_top_k(monkeypatch):
    use_engines(monkeypatch, fakes.Engines(event=[("e.id IN", [event_row("ev1")])]))
    rag = CountingRag([vector_hit("ev1", 0.8)])
    monkeypatch.setattr(customer.rag_engine, "search_events", rag)
    client = make_client()
    client.post("/api/customer/chat-search", json={"query": "nhạc", "top_k": 3})
    client.post("/api/customer/chat-search", json={"query": "nhạc", "top_k": 5})
    assert rag.calls == 2


def test_search_cache_uses_redis_when_available_and_falls_back_when_it_fails(monkeypatch):
    store = {}

    class FakeRedis:
        def get(self, key):
            return store.get(key)

        def set(self, key, value, ex=None):
            store[key] = value
            assert ex == settings.ai_search_cache_seconds

    monkeypatch.setattr(settings, "redis_url", "redis://fake")
    monkeypatch.setattr(search_cache, "_redis_factory", lambda url: FakeRedis())
    search_cache.put("k", {"a": 1})
    assert search_cache.get("k") == {"a": 1} and "k" in store

    class Broken:
        def get(self, key):
            raise RuntimeError("down")

        def set(self, *a, **k):
            raise RuntimeError("down")

    search_cache.reset_for_tests()
    monkeypatch.setattr(search_cache, "_redis_factory", lambda url: Broken())
    search_cache.put("k2", {"b": 2})  # falls back to the in-process LRU, no exception
    assert search_cache.get("k2") == {"b": 2}


def test_in_process_cache_keeps_at_most_200_entries():
    for i in range(250):
        search_cache.put(f"k{i}", {"i": i})
    assert len(search_cache._lru) == 200
    assert search_cache.get("k0") is None and search_cache.get("k249") == {"i": 249}


# ------------------------------------------------------------------ honest scores and shape
def test_keyword_only_hits_have_null_relevance_and_matches_have_no_content(monkeypatch):
    engines = fakes.Engines(event=[
        ("e.id IN", [event_row("ev1", "Vector")]),
        ("ILIKE", [event_row("ev2", "Keyword")]),
    ])
    use_engines(monkeypatch, engines)
    monkeypatch.setattr(customer.rag_engine, "search_events", CountingRag([vector_hit("ev1", 0.8123)]))
    body = make_client().post("/api/customer/chat-search", json={"query": "show"}).json()

    scores = {e["id"]: e["relevanceScore"] for e in body["events"]}
    assert scores == {"ev1": 0.812, "ev2": None}
    assert body["matches"] == [{"event_id": "ev1", "score": 0.812}]
    assert "content" not in str(body) and "SECRET" not in str(body)
    assert body["search_mode"] == "vector" and body["degraded"] is False
    assert 0.85 not in scores.values()


def test_threshold_and_validity_are_in_the_sql_before_the_top_k_cut():
    sql = str(rag_module.VECTOR_SEARCH_SQL)
    for needle in ("e.event_status IN ('LIVE', 'APPROVED')", "e.is_private IS NOT TRUE", "e.end_time IS NULL OR e.end_time > now()"):
        assert needle in sql
    assert sql.index(":min_score") < sql.index("LIMIT :top_k")
    assert sql.index("e.is_private") < sql.index("LIMIT :top_k")
    assert customer_sql.SEARCH_MIN_SCORE == 0.35


def test_rag_engine_runs_one_statement_with_threshold_and_limit(monkeypatch):
    captured = []

    class Db(fakes.FakeConn):
        def execute(self, statement, params=None):
            captured.append(params)
            return fakes.FakeResult([fakes.row(event_id="ev1", content="c", score=0.7)])

    monkeypatch.setattr(rag_module.rag_engine, "embeddings", SimpleNamespace(embed_query=lambda q: [0.5, 0.25]))
    hits = rag_module.rag_engine.search_events(Db("event", [], []), "nhạc", top_k=4)
    assert captured[0]["min_score"] == 0.35 and captured[0]["top_k"] == 4 and captured[0]["vec"] == "[0.5,0.25]"
    assert hits[0].EventVector.event_id == "ev1" and round(1 - hits[0].distance, 3) == 0.7


# ------------------------------------------------------------------ degradation and errors
def test_embedding_failure_degrades_to_keyword_search_with_200(monkeypatch):
    engines = fakes.Engines(event=[("ILIKE", [event_row("ev2", "Keyword")])])
    use_engines(monkeypatch, engines)

    def boom(q):
        raise RuntimeError("quota exceeded secret-key-123")

    monkeypatch.setattr(customer.rag_engine, "embeddings", SimpleNamespace(embed_query=boom))
    response = make_client().post("/api/customer/chat-search", json={"query": "show"})
    body = response.json()
    assert response.status_code == 200
    assert body["search_mode"] == "keyword" and body["degraded"] is True
    assert body["events"][0]["id"] == "ev2" and body["events"][0]["relevanceScore"] is None
    assert body["matches"] == []
    assert "secret-key" not in response.text
    # a degraded answer is not cached
    make_client().post("/api/customer/chat-search", json={"query": "show"})
    assert len([s for s in engines.statements if "ILIKE" in s.sql]) == 2


def test_database_not_configured_is_503_ai_db_not_configured(monkeypatch):
    monkeypatch.setattr(settings, "chatbot_event_database_url", "")
    response = make_client().post("/api/customer/chat-search", json={"query": "show"})
    assert response.status_code == 503
    assert response.json()["code"] == "AI_DB_NOT_CONFIGURED"


def test_unreachable_database_is_503_unavailable(monkeypatch):
    err = OperationalError("SELECT 1", {}, Exception("password authentication failed for user x"))
    engine = fakes.FakeEngine("event", [], [], connect_failure=err)
    monkeypatch.setattr(customer, "get_chatbot_engine", lambda kind: engine)
    monkeypatch.setattr(customer.rag_engine, "search_events", CountingRag([]))
    response = make_client().post("/api/customer/chat-search", json={"query": "show"})
    assert response.status_code == 503
    assert response.json()["code"] == "AI_UNAVAILABLE"
    assert "password" not in response.text


def test_keyword_sql_filters_validity_and_escapes_wildcards(monkeypatch):
    engines = fakes.Engines()
    use_engines(monkeypatch, engines)
    monkeypatch.setattr(customer.rag_engine, "search_events", CountingRag([]))
    make_client().post("/api/customer/chat-search", json={"query": "100%_off"})
    kw = next(s for s in engines.statements if "ILIKE" in s.sql)
    assert "is_private IS NOT TRUE" in kw.sql and "end_time > now()" in kw.sql
    assert kw.params["kw"] == "%100\\%\\_off%"
    assert {s.kind for s in engines.statements} == {"event"}


# ------------------------------------------------------------------ chat error contract
@pytest.mark.parametrize("failure,status,code", [
    (RuntimeError("llm down"), 503, "AI_UNAVAILABLE"),
    (ai_error(AI_GENERATION_FAILED), 502, "AI_GENERATION_FAILED"),
])
def test_chat_failures_map_to_error_statuses(monkeypatch, failure, status, code):
    def broken(message, history, db):
        raise failure

    monkeypatch.setattr(customer.booking_agent, "chat", broken)
    response = make_client().post("/api/customer/smart-booking-chat", json={"message": "hi"})
    assert response.status_code == status and response.json()["code"] == code
    assert "llm down" not in response.text
