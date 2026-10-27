"""Limits on the public chatbot endpoints, and the rule that no code reuses a closed connection. No database needed."""
import pathlib
import re
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.endpoints import customer
from app.db.session import get_db


class FakeResult:
    def fetchall(self):
        return []


class FakeDb:
    """Stands in for the session when a request must not reach a database."""
    def execute(self, *args, **kwargs):
        return FakeResult()

    def rollback(self):
        pass


@pytest.fixture
def client(monkeypatch):
    app = FastAPI()
    app.include_router(customer.router, prefix="/api/customer")
    app.dependency_overrides[get_db] = lambda: FakeDb()
    monkeypatch.setattr(customer.rag_engine, "search_events", lambda db, query, top_k=3: [])
    return TestClient(app)


@pytest.fixture
def chat_calls(monkeypatch):
    calls = []

    def fake_chat(message, history, db):
        calls.append(SimpleNamespace(message=message, history=history))
        return {"reply": "ok", "system_actions": []}

    monkeypatch.setattr(customer.booking_agent, "chat", fake_chat)
    return calls


def test_a_question_of_500_characters_is_accepted_and_501_is_refused_with_a_clear_code(client):
    ok = client.post("/api/customer/chat-search", json={"query": "a" * 500})
    assert ok.status_code == 200

    refused = client.post("/api/customer/chat-search", json={"query": "a" * 501})
    assert refused.status_code == 422
    assert refused.json()["detail"][0]["type"] == "string_too_long"
    assert refused.json()["detail"][0]["loc"] == ["body", "query"]


def test_top_k_is_limited_to_10(client):
    assert client.post("/api/customer/chat-search", json={"query": "nhạc", "top_k": 10}).status_code == 200
    too_big = client.post("/api/customer/chat-search", json={"query": "nhạc", "top_k": 11})
    assert too_big.status_code == 422
    assert too_big.json()["detail"][0]["type"] == "less_than_equal"
    assert client.post("/api/customer/chat-search", json={"query": "nhạc", "top_k": 0}).status_code == 422


def test_the_chat_message_is_limited_to_500_characters(client, chat_calls):
    assert client.post("/api/customer/smart-booking-chat", json={"message": "m" * 500}).status_code == 200
    refused = client.post("/api/customer/smart-booking-chat", json={"message": "m" * 501})
    assert refused.status_code == 422
    assert refused.json()["detail"][0]["type"] == "string_too_long"
    assert len(chat_calls) == 1


def test_a_long_history_is_cut_to_the_latest_ten_entries_not_refused(client, chat_calls):
    history = [{"role": "user" if i % 2 == 0 else "assistant", "content": f"turn {i}"} for i in range(25)]
    response = client.post("/api/customer/smart-booking-chat", json={"message": "tiếp", "history": history})

    assert response.status_code == 200
    sent = chat_calls[0].history
    assert len(sent) == 10
    assert [h["content"] for h in sent] == [f"turn {i}" for i in range(15, 25)]


def test_a_short_history_is_passed_unchanged(client, chat_calls):
    history = [{"role": "user", "content": "xin chào"}, {"role": "assistant", "content": "chào bạn"}]
    client.post("/api/customer/smart-booking-chat", json={"message": "tiếp", "history": history})

    assert chat_calls[0].history == history


def test_one_oversized_history_entry_is_shortened(client, chat_calls):
    history = [{"role": "assistant", "content": "x" * 5000}]
    client.post("/api/customer/smart-booking-chat", json={"message": "tiếp", "history": history})

    assert len(chat_calls[0].history[0]["content"]) == 2000


def test_empty_body_is_still_422_and_a_blank_question_still_answers_200_with_nothing(client):
    assert client.post("/api/customer/chat-search", json={}).status_code == 422
    assert client.post("/api/customer/smart-booking-chat", json={}).status_code == 422
    blank = client.post("/api/customer/chat-search", json={"query": "   "})
    assert blank.status_code == 200
    assert blank.json() == {"query": "   ", "total": 0, "matches": [], "events": []}


def test_chat_failures_still_answer_with_the_apology_text(client, monkeypatch):
    def broken(message, history, db):
        raise RuntimeError("llm down")

    monkeypatch.setattr(customer.booking_agent, "chat", broken)
    response = client.post("/api/customer/smart-booking-chat", json={"message": "hi"})
    assert response.status_code == 200
    assert response.json()["system_actions"] == []
    assert "sự cố" in response.json()["reply"]


def test_no_code_uses_a_session_connection_as_a_context_manager():
    """`with db.connection() as conn` closes the session's connection on exit; the next use of the session then fails
    with ResourceClosedError. This is what broke chat-search for every question that had a vector match."""
    root = pathlib.Path(__file__).resolve().parent.parent / "app"
    offenders = [
        str(path.relative_to(root))
        for path in root.rglob("*.py")
        if re.search(r"with\s+\w+\.connection\(\)", path.read_text(encoding="utf-8"))
    ]
    assert offenders == []
