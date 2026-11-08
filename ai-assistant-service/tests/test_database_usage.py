"""Repeated and parallel use of the chatbot's database code against the dev databases (read only: SELECT only).

The chatbot reads through the engines of app.db.engines.get_chatbot_engine('event' | 'ticket'). Here both engines are
pointed at the dev databases (with the shared dev account, so the GRANTs of the ai_chatbot_ro role are NOT exercised:
only the SQL itself is). Run with the dev databases reachable and PGOPTIONS='-c default_transaction_read_only=on':
    RUN_DB_TESTS=1 EVENT_DB_URL=... TICKET_DB_URL=... pytest tests/test_database_usage.py
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
    pytest.mark.skipif(os.getenv("RUN_DB_TESTS") != "1", reason="set RUN_DB_TESTS=1 and EVENT_DB_URL / TICKET_DB_URL"),
]

SEQUENTIAL_CALLS = 20
PARALLEL_REQUESTS = 5


@pytest.fixture(scope="module")
def modules():
    from app.core.config import settings
    from app.db import engines
    from app.api.endpoints import customer
    from app.services.ai import tools

    saved = (settings.chatbot_event_database_url, settings.chatbot_ticket_database_url, settings.ai_rl_customer_search_per_minute)
    settings.chatbot_event_database_url = os.environ["EVENT_DB_URL"]
    settings.chatbot_ticket_database_url = os.environ["TICKET_DB_URL"]
    settings.ai_rl_customer_search_per_minute = 100000  # the repeated calls below must not hit the rate limit
    engines.dispose_all()
    yield SimpleNamespace(customer=customer, tools=tools, engines=engines)
    engines.dispose_all()
    (settings.chatbot_event_database_url, settings.chatbot_ticket_database_url, settings.ai_rl_customer_search_per_minute) = saved


@pytest.fixture(scope="module")
def live_event_id(modules):
    with modules.engines.get_chatbot_engine("event").connect() as conn:
        row = conn.execute(text(
            "SELECT id FROM events WHERE event_status = 'LIVE' AND (end_time IS NULL OR end_time >= NOW()) ORDER BY created_at DESC LIMIT 1"
        )).fetchone() or conn.execute(text("SELECT id FROM events LIMIT 1")).fetchone()
    if row is None:
        pytest.skip("no event in the dev database")
    return row.id


@pytest.fixture
def client(modules, monkeypatch):
    from app.core.errors import install_error_handlers
    from app.services.ai.rag_engine import rag_engine

    # no embedding call is made: a fixed vector stands in, so the real vector SQL and the keyword SQL both run
    monkeypatch.setattr(rag_engine, "embeddings", SimpleNamespace(embed_query=lambda text_: [1.0] + [0.0] * 3071))
    app = FastAPI()
    install_error_handlers(app)
    app.include_router(modules.customer.router, prefix="/api/customer")
    return app


def _no_connection_left_checked_out(modules):
    for kind in ("event", "ticket"):
        assert modules.engines.get_chatbot_engine(kind).pool.checkedout() == 0


def test_chat_search_twenty_times_in_a_row(modules, client):
    with TestClient(client) as http:
        for _ in range(SEQUENTIAL_CALLS):
            response = http.post("/api/customer/chat-search", json={"query": "show ca nhạc ở TP.HCM"})
            assert response.status_code == 200, response.text
            body = response.json()
            assert body["query"] == "show ca nhạc ở TP.HCM"
            assert body["search_mode"] in ("vector", "keyword")
    _no_connection_left_checked_out(modules)


def test_chat_search_five_requests_at_once(modules, client):
    def worker(_):
        with TestClient(client) as http:
            return [http.post("/api/customer/chat-search", json={"query": f"nhạc {i}"}).status_code for i in range(4)]

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
    # a database failure raises AiError now; the tools answer with a status for every normal outcome
    assert seats["status"] != "error", seats.get("message")
    assert seatmap["status"] != "error", seatmap.get("message")


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
    from app.services.ai.rag_engine import rag_engine

    monkeypatch.setattr(rag_engine, "embeddings", SimpleNamespace(embed_query=lambda text_: [1.0] + [0.0] * 3071))
    with modules.engines.get_chatbot_engine("event").connect() as conn:
        hits = rag_engine.search_events(conn, "nhạc", top_k=3, min_score=-1.0)
        assert not conn.in_transaction()
        for hit in hits:  # plain objects: usable after the connection is gone
            assert hit.EventVector.event_id and isinstance(hit.distance, float)
    _no_connection_left_checked_out(modules)
