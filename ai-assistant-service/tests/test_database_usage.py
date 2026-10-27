"""Repeated and parallel use of the chatbot's database code against the dev databases (read only: SELECT only).

Run with the dev databases reachable:
    RUN_DB_TESTS=1 DATABASE_URL=... EVENT_DB_URL=... TICKET_DB_URL=... pytest
Without RUN_DB_TESTS these are skipped.
"""
import json
import os
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import text

pytestmark = [
    pytest.mark.db,
    pytest.mark.skipif(os.getenv("RUN_DB_TESTS") != "1", reason="set RUN_DB_TESTS=1 and the three database URLs"),
]

SEQUENTIAL_CALLS = 20
PARALLEL_REQUESTS = 5


@pytest.fixture(scope="module")
def modules():
    from app.api.endpoints import customer
    from app.db.session import engine, get_db
    from app.services.ai import tools
    return SimpleNamespace(customer=customer, tools=tools, get_db=get_db, session_engine=engine)


@pytest.fixture(scope="module")
def live_event_id(modules):
    with modules.tools.event_engine.connect() as conn:
        row = conn.execute(text(
            "SELECT id FROM events WHERE event_status = 'LIVE' AND (end_time IS NULL OR end_time >= NOW()) ORDER BY created_at DESC LIMIT 1"
        )).fetchone() or conn.execute(text("SELECT id FROM events LIMIT 1")).fetchone()
    if row is None:
        pytest.skip("no event in the dev database")
    return row.id


@pytest.fixture
def client(modules, monkeypatch, live_event_id):
    # a vector hit on a real event, so the semantic query runs and the keyword query runs after it: the exact sequence
    # that used to raise ResourceClosedError
    hit = SimpleNamespace(EventVector=SimpleNamespace(event_id=live_event_id, content="x"), distance=0.1)
    monkeypatch.setattr(modules.customer.rag_engine, "search_events", lambda db, query, top_k=3: [hit])
    app = FastAPI()
    app.include_router(modules.customer.router, prefix="/api/customer")
    return app


def _no_connection_left_checked_out(modules):
    for engine in (modules.session_engine, modules.tools.event_engine, modules.tools.ticket_engine):
        assert engine.pool.checkedout() == 0


def test_chat_search_twenty_times_in_a_row(modules, client):
    with TestClient(client) as http:
        for _ in range(SEQUENTIAL_CALLS):
            response = http.post("/api/customer/chat-search", json={"query": "show ca nhạc ở TP.HCM"})
            assert response.status_code == 200, response.text
            assert response.json()["query"] == "show ca nhạc ở TP.HCM"
    _no_connection_left_checked_out(modules)


def test_chat_search_five_requests_at_once(modules, client):
    def worker(_):
        with TestClient(client) as http:
            return [http.post("/api/customer/chat-search", json={"query": "nhạc"}).status_code for _ in range(4)]

    with ThreadPoolExecutor(PARALLEL_REQUESTS) as pool:
        codes = [code for batch in pool.map(worker, range(PARALLEL_REQUESTS)) for code in batch]
    assert codes == [200] * (PARALLEL_REQUESTS * 4)
    _no_connection_left_checked_out(modules)


def _call_tools(modules, event_id):
    cards = json.loads(modules.tools.show_event_cards.invoke({"event_ids": [event_id]}))
    types = json.loads(modules.tools.show_ticket_types.invoke({"event_id": event_id}))
    seats = json.loads(modules.tools.find_adjacent_seats.invoke({"event_id": event_id, "zone": "VIP", "num_seats": 2}))
    seatmap = json.loads(modules.tools.show_seatmap.invoke({"event_id": event_id}))
    return cards, types, seats, seatmap


def _assert_tool_results(event_id, cards, types, seats, seatmap):
    assert cards["status"] == "show_cards"
    assert types["status"] == "success" and types["event_id"] == event_id
    # find_adjacent_seats reports database trouble as status "error" with the message
    assert seats["status"] != "error", seats.get("message")
    assert seatmap["status"] == "success"


def test_the_tools_twenty_times_in_a_row(modules, live_event_id):
    for _ in range(SEQUENTIAL_CALLS):
        _assert_tool_results(live_event_id, *_call_tools(modules, live_event_id))
    _no_connection_left_checked_out(modules)


def test_the_tools_from_five_threads_at_once(modules, live_event_id):
    def worker(_):
        for _ in range(4):
            _assert_tool_results(live_event_id, *_call_tools(modules, live_event_id))
        return True

    with ThreadPoolExecutor(PARALLEL_REQUESTS) as pool:
        assert all(pool.map(worker, range(PARALLEL_REQUESTS)))
    _no_connection_left_checked_out(modules)


def test_the_vector_search_hands_its_connection_back_before_returning(modules, monkeypatch):
    from app.db.session import SessionLocal
    from app.services.ai.rag_engine import rag_engine

    monkeypatch.setattr(rag_engine, "embeddings", SimpleNamespace(embed_query=lambda text_: [1.0] + [0.0] * 3071))
    db = SessionLocal()
    try:
        hits = rag_engine.search_events(db, "nhạc", top_k=3)
        assert not db.in_transaction()
        for hit in hits:  # plain objects: usable after the session is gone
            assert hit.EventVector.event_id and isinstance(hit.distance, float)
    finally:
        db.close()
    _no_connection_left_checked_out(modules)
