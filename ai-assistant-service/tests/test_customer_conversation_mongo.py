"""Exercise the single customer conversation against an isolated MongoDB database."""
import os
from concurrent.futures import ThreadPoolExecutor
from threading import Event
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from pymongo import MongoClient

from app.api.endpoints import customer
from app.core.config import settings
from app.core.errors import install_error_handlers
from app.core.security import get_verifier

HISTORY = "/api/customer/chat-history"
CHAT = "/api/customer/smart-booking-chat"
ACCOUNT = {"Authorization": "Bearer buyer-a"}
GUEST = {"X-Customer-Chat-Guest": "a" * 64}


@pytest.fixture
def api(monkeypatch):
    app = FastAPI()
    install_error_handlers(app)
    app.include_router(customer.router, prefix="/api/customer")
    app.dependency_overrides[get_verifier] = lambda: SimpleNamespace(
        verify=lambda token: {"iss": "https://realm.example", "sub": token}
    )
    calls = []

    def chat(message, history, db, **kwargs):
        calls.append((message, history))
        return {"reply": "Đã tìm thấy vé", "system_actions": [{"action": "SHOW_EVENT_CARDS", "database_data": {"data": []}}]}

    monkeypatch.setattr(customer.booking_agent, "chat", chat)
    with TestClient(app) as client:
        yield client, calls


@pytest.fixture
def mongo(api, monkeypatch):
    uri = os.environ.get("MONGODB_TEST_URI")
    if not uri:
        pytest.skip("Set MONGODB_TEST_URI to run MongoDB integration tests.")
    database = f"haloticket_test_customer_{uuid4().hex}"
    monkeypatch.setattr(settings, "mongodb_uri", uri)
    monkeypatch.setattr(settings, "mongodb_database", database)
    with MongoClient(uri, serverSelectionTimeoutMS=3000) as client:
        try:
            yield *api, client[database]
        finally:
            client.drop_database(database)


def _turn(message="Tìm vé", **extra):
    return {"message": message, "request_id": str(uuid4()), **extra}


def test_history_needs_an_owner_and_reports_unconfigured_storage(api, monkeypatch):
    client, _ = api
    monkeypatch.setattr(settings, "mongodb_uri", "")
    assert client.get(HISTORY).status_code == 401
    assert client.get(HISTORY, headers=ACCOUNT).json()["code"] == "AI_DB_NOT_CONFIGURED"
    wrong_subject = {**ACCOUNT, "X-Customer-Chat-Subject": "buyer-b"}
    assert client.delete(HISTORY, headers=wrong_subject).status_code == 401


def test_reloads_one_conversation_and_retries_without_a_duplicate(mongo):
    client, calls, db = mongo
    first = _turn("Chọn sự kiện (Mã: ev-1)", display_message="Chọn sự kiện")
    sent = client.post(CHAT, headers=ACCOUNT, json=first)
    assert sent.status_code == 200, sent.text
    history = sent.json()["conversation"]
    assert history == client.get(HISTORY, headers=ACCOUNT).json()
    assert history["messages"][0]["content"] == "Chọn sự kiện"
    assert "prompt" not in history["messages"][0]
    assert history["messages"][1]["system_actions"][0]["action"] == "SHOW_EVENT_CARDS"
    assert client.post(CHAT, headers=ACCOUNT, json=first).json() == sent.json()
    assert client.post(CHAT, headers=ACCOUNT, json={**first, "message": "Khác"}).status_code == 409
    assert len(calls) == 1

    next_turn = _turn("Còn vé không?", history=[{"role": "user", "content": "client override"}])
    assert client.post(CHAT, headers=ACCOUNT, json=next_turn).status_code == 200
    assert calls[1][1] == [
        {"role": "user", "content": first["message"]},
        {"role": "assistant", "content": "Đã tìm thấy vé"},
    ]
    assert db.customer_conversations.count_documents({}) == 1


def test_guest_import_account_isolation_and_reset(mongo):
    client, _, _ = mongo
    legacy = {"messages": [{"role": "user", "content": "Tin cũ", "created_at": "2026-11-24T01:02:03Z"}]}
    imported = client.put(HISTORY, headers=GUEST, json=legacy)
    assert imported.status_code == 200, imported.text
    assert imported.json()["messages"][0]["content"] == "Tin cũ"
    assert client.put(HISTORY, headers=GUEST, json=legacy).json() == imported.json()
    assert client.get(HISTORY, headers=ACCOUNT).json()["messages"] == []
    assert client.get(HISTORY, headers={"X-Customer-Chat-Guest": "b" * 64}).json()["messages"] == []
    assert client.delete(HISTORY, headers=GUEST).status_code == 200
    assert client.put(HISTORY, headers=GUEST, json=legacy).json()["messages"] == []


def test_reset_during_generation_keeps_the_same_conversation_empty(mongo, monkeypatch):
    client, _, db = mongo
    entered = Event()
    release = Event()

    def chat(*args, **kwargs):
        entered.set()
        assert release.wait(5)
        return {"reply": "Old reply", "system_actions": []}

    monkeypatch.setattr(customer.booking_agent, "chat", chat)
    with ThreadPoolExecutor(max_workers=1) as executor:
        pending = executor.submit(client.post, CHAT, headers=GUEST, json=_turn())
        try:
            assert entered.wait(5)
            assert client.delete(HISTORY, headers=GUEST).status_code == 200
        finally:
            release.set()
        assert pending.result().status_code == 409
    assert client.get(HISTORY, headers=GUEST).json()["messages"] == []
    assert db.customer_conversations.count_documents({}) == 1


def test_retry_from_before_reset_cannot_recreate_deleted_messages(mongo):
    client, calls, _ = mongo
    headers = {"Authorization": "Bearer buyer-reset"}
    first = _turn(generation=0)
    assert client.post(CHAT, headers=headers, json=first).status_code == 200
    reset = client.delete(HISTORY, headers=headers).json()
    assert reset["generation"] == 1
    rejected = client.post(CHAT, headers=headers, json=first)
    assert rejected.status_code == 409
    assert rejected.json()["code"] == "AI_CHAT_RESET"
    assert len(calls) == 1
    assert client.get(HISTORY, headers=headers).json()["messages"] == []
    assert client.post(CHAT, headers=headers, json=_turn(generation=1)).status_code == 200


def test_import_confirms_its_source_and_older_messages_are_reachable(mongo):
    client, _, _ = mongo
    headers = {"Authorization": "Bearer buyer-import"}
    entries = [{"role": "user", "content": f"Tin nhắn {index}", "created_at": "2026-11-24T01:02:03Z"} for index in range(101)]
    imported = client.put(HISTORY, headers=headers, json={"messages": entries}).json()
    assert imported["imported"] is True
    assert imported["messages"][0]["content"] == "Tin nhắn 1"
    older = client.get(HISTORY, headers=headers, params={"before": imported["older_cursor"]}).json()
    assert [message["content"] for message in older["messages"]] == ["Tin nhắn 0"]
    assert older["older_cursor"] is None
    other = client.put(HISTORY, headers=headers, json={"messages": entries[-1:]}).json()
    assert other["imported"] is False
