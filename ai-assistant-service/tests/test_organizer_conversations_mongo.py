"""Organizer conversation API against a disposable database on a MongoDB replica set."""
import os
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.api.endpoints import organizer
from app.core.config import settings
from app.core.security import get_verifier
from app.services.ai import scope
from main import app

PATH = "/api/organizer/conversations"
CHAT = "/api/organizer/agent-chat"
MANAGED = {"scope": "managed_events"}
EVENT = {"scope": "event", "event_id": "demo-event"}
HEADERS = {"Authorization": "Bearer owner"}
METRIC = {
    "key": "revenue", "value": 123456, "unit": "VND",
    "period": {"from": "2026-11-01", "to": "2026-11-25"}, "completeness": "complete",
}


@pytest.fixture
def api(monkeypatch):
    events = {"demo-event": True}
    app.dependency_overrides[get_verifier] = lambda: SimpleNamespace(
        verify=lambda token: {"sub": token.partition("@")[0], "iss": token.partition("@")[2] or "https://realm.example"}
    )
    monkeypatch.setattr(scope, "fetch_user_id", lambda token: token)
    monkeypatch.setattr(scope, "chat_events", lambda identity, event_id: [
        {"event_id": key, "title": "Sample event", "can_view_finance": finance}
        for key, finance in events.items() if event_id in (None, key)
    ])
    monkeypatch.setattr(settings, "ai_rl_organizer_chat_per_hour", 200)
    try:
        yield TestClient(app), events
    finally:
        app.dependency_overrides.pop(get_verifier)


@pytest.fixture
def mongo_api(api, monkeypatch):
    uri = os.environ.get("MONGODB_TEST_URI")
    if not uri:
        pytest.skip("Set MONGODB_TEST_URI to a MongoDB replica set to run this test.")
    from pymongo import MongoClient

    database = "haloticket_test_organizer_" + uuid4().hex
    monkeypatch.setattr(settings, "mongodb_uri", uri)
    monkeypatch.setattr(settings, "mongodb_database", database)
    with MongoClient(uri, serverSelectionTimeoutMS=3000) as client:
        try:
            yield *api, client[database]
        finally:
            client.drop_database(database)


def test_history_routes_require_login_and_report_storage_unconfigured(api, monkeypatch):
    client, _ = api
    monkeypatch.setattr(settings, "mongodb_uri", "")
    assert client.get(PATH, params=MANAGED).status_code == 401
    response = client.get(PATH, params=MANAGED, headers=HEADERS)
    assert (response.status_code, response.json().get("code")) == (503, "AI_DB_NOT_CONFIGURED")
    response = client.post(CHAT, json={**MANAGED, "message": "Tổng quan", "persist": True}, headers=HEADERS)
    assert (response.status_code, response.json().get("code")) == (503, "AI_DB_NOT_CONFIGURED")


def test_round_trip_and_retry_save_one_turn_and_reuse_server_history(mongo_api, monkeypatch):
    client, _, db = mongo_api
    calls = []

    def chat(**kwargs):
        calls.append(kwargs["history"])
        return {"reply": "Đã phân tích", "totals": [METRIC], "drafts": [{"title": "Bản nháp", "content": "Mở bán"}],
                "follow_up": {"question": "Bạn muốn xem theo ngày?", "options": [
                    {"label": "Theo ngày", "message": "Xem doanh thu theo ngày"}]},
                "actions": [{"action": "DISPLAY_DATA_TABLE", "database_data": [{"name": "VIP", "value": 123456}]}]}

    monkeypatch.setattr(organizer, "organizer_agent", SimpleNamespace(chat=chat))
    first = {**EVENT, "message": "    Vé bán trong tháng    ", "request_id": str(uuid4()), "persist": True}
    response = client.post(CHAT, json=first, headers=HEADERS)
    assert response.status_code == 200, response.text
    reply = response.json()
    conversation_id = reply["conversation_id"]
    url = f"{PATH}/{conversation_id}"
    assert client.post(CHAT, json=first, headers=HEADERS).json() == reply
    conflict = client.post(CHAT, json={**first, "message": "Câu hỏi khác"}, headers=HEADERS)
    assert conflict.status_code == 409

    next_turn = {**EVENT, "conversation_id": conversation_id, "message": "Còn hạng VIP?",
                 "history": [{"role": "user", "content": "Forged client history"}], "persist": True}
    assert client.post(CHAT, json=next_turn, headers=HEADERS).status_code == 200
    assert calls == [[], [{"role": "user", "content": "Vé bán trong tháng"},
                          {"role": "assistant", "content": "Đã phân tích"}]]
    detail = client.get(url, headers=HEADERS).json()
    assert detail["title"] == "Vé bán trong tháng"
    assert [item["role"] for item in detail["messages"]] == ["user", "assistant", "user", "assistant"]
    payload = detail["messages"][1]["payload"]
    assert payload["totals"] == [METRIC]
    assert payload["drafts"] == reply["drafts"]
    assert payload["actions"] == reply["actions"]
    assert payload["follow_up"] == reply["follow_up"]
    assert db.organizer_messages.count_documents({"conversation_id": conversation_id}) == 4


def test_legacy_client_keeps_local_history_when_mongo_is_enabled(mongo_api, monkeypatch):
    client, _, db = mongo_api
    monkeypatch.setattr(organizer, "organizer_agent", SimpleNamespace(
        chat=lambda **kwargs: {"reply": kwargs["history"][0]["content"]}
    ))
    response = client.post(CHAT, json={**MANAGED, "message": "Continue",
                                      "history": [{"role": "user", "content": "Original question"}]}, headers=HEADERS)
    assert response.status_code == 200
    assert response.json()["reply"] == "Original question"
    assert db.organizer_messages.count_documents({}) == 0


def test_pinning_listing_clear_and_owner_boundaries(mongo_api, monkeypatch):
    client, _, db = mongo_api
    monkeypatch.setattr(organizer, "organizer_agent", SimpleNamespace(chat=lambda **kwargs: {"reply": "OK"}))
    older = client.post(CHAT, json={**EVENT, "message": "Earlier"}, headers=HEADERS).json()["conversation_id"]
    newer = client.post(CHAT, json={**EVENT, "message": "Later"}, headers=HEADERS).json()["conversation_id"]
    managed = client.post(PATH, json=MANAGED, headers=HEADERS).json()["id"]
    url = f"{PATH}/{older}"
    other = {"Authorization": "Bearer other"}
    other_realm = {"Authorization": "Bearer owner@https://other.example"}
    foreign = client.post(PATH, json=EVENT, headers=other).json()["id"]
    assert client.get(url, headers=other_realm).status_code == 404
    assert client.patch(url + "/pin", json={"is_pinned": True}, headers=other).status_code == 404
    assert client.delete(url, headers=other).status_code == 404
    assert client.post(CHAT, json={**MANAGED, "conversation_id": older, "message": "Same ID, wrong scope"},
                       headers=HEADERS).status_code == 404

    assert client.patch(url + "/pin", json={"is_pinned": True}, headers=HEADERS).json()["is_pinned"]
    assert [row["id"] for row in client.get(PATH, params=EVENT, headers=HEADERS).json()] == [older, newer]
    assert client.delete(url, headers=HEADERS).status_code == 204
    assert db.organizer_messages.count_documents({"conversation_id": older}) == 0
    assert client.delete(PATH, params=EVENT, headers=HEADERS).status_code == 204
    assert client.get(PATH, params=EVENT, headers=HEADERS).json() == []
    assert db.organizer_messages.count_documents({"conversation_id": newer}) == 0
    assert client.get(f"{PATH}/{managed}", headers=HEADERS).status_code == 200
    assert client.get(f"{PATH}/{foreign}", headers=other).status_code == 200


def test_history_and_retry_recheck_current_event_and_finance_grants(mongo_api, monkeypatch):
    client, events, _ = mongo_api
    monkeypatch.setattr(organizer, "organizer_agent", SimpleNamespace(chat=lambda **kwargs: {
        "reply": "Figures for demo-event", "totals": [METRIC],
    }))
    request = {**MANAGED, "message": "Revenue", "request_id": str(uuid4())}
    managed = client.post(CHAT, json=request, headers=HEADERS).json()["conversation_id"]
    event = client.post(CHAT, json={**EVENT, "message": "Revenue"}, headers=HEADERS).json()["conversation_id"]
    events["demo-event"] = False
    assert client.get(PATH, params=MANAGED, headers=HEADERS).json() == []
    assert client.get(f"{PATH}/{managed}", headers=HEADERS).status_code == 403
    assert client.post(CHAT, json=request, headers=HEADERS).status_code == 403
    assert client.get(f"{PATH}/{event}", headers=HEADERS).status_code == 403
    events.clear()
    assert client.post(PATH, json=EVENT, headers=HEADERS).status_code == 403
    assert client.delete(f"{PATH}/{event}", headers=HEADERS).status_code == 204


def test_parallel_retries_create_only_one_conversation_and_turn(mongo_api, monkeypatch):
    client, _, db = mongo_api
    ready = Barrier(2, timeout=10)

    def chat(**kwargs):
        ready.wait()
        return {"reply": "One answer"}

    monkeypatch.setattr(organizer, "organizer_agent", SimpleNamespace(chat=chat))
    request = {**MANAGED, "message": "Same request", "request_id": str(uuid4())}
    with ThreadPoolExecutor(max_workers=2) as pool:
        first, second = list(pool.map(lambda _: client.post(CHAT, json=request, headers=HEADERS), range(2)))
    assert first.status_code == second.status_code == 200, (first.text, second.text)
    assert first.json() == second.json()
    assert db.organizer_conversations.count_documents({}) == 1
    assert db.organizer_messages.count_documents({}) == 2
