import os
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from pymongo import MongoClient

from app.api.endpoints import admin_intel
from app.core.config import settings
from app.core.security import get_verifier
from main import app


def test_admin_chat_history_is_private_and_restores_saved_turns(monkeypatch):
    uri = os.getenv("MONGODB_TEST_URI")
    if not uri:
        pytest.skip("MongoDB replica set required")
    database = "haloticket_test_admin_" + uuid4().hex
    monkeypatch.setattr(settings, "mongodb_uri", uri)
    monkeypatch.setattr(settings, "mongodb_database", database)
    monkeypatch.setattr(app, "dependency_overrides", {get_verifier: lambda: SimpleNamespace(verify=lambda token: {
        "iss": "https://other.realm.test" if token.endswith("@other") else "https://realm.test",
        "sub": token.partition("@")[0], "roles": [] if token == "user" else ["ADMIN"],
    })})
    calls = []

    def reply(message, history, *_):
        calls.append(history)
        return {"reply": "Test reply", "tools_used": ["get_kpis"], "grounded": True}

    monkeypatch.setattr(admin_intel.assistant, "run", reply)
    path = "/api/admin/assistant-conversations"
    send = "/api/admin/assistant-chat"
    owner = {"Authorization": "Bearer admin-a"}
    other = {"Authorization": "Bearer admin-b"}
    with MongoClient(uri) as mongo:
        try:
            client = TestClient(app)
            assert client.get(path).status_code == 401
            assert client.get(path, headers={"Authorization": "Bearer user"}).status_code == 403
            conversation = str(uuid4())
            body = {"id": conversation}
            assert client.post(path, headers=owner, json=body).json()["id"] == conversation
            assert client.post(path, headers=owner, json=body).json()["id"] == conversation
            detail_url = f"{path}/{conversation}"
            data = {"conversation_id": conversation, "request_id": str(uuid4()), "message": "Xem số liệu"}
            first = client.post(send, headers=owner, json=data)
            assert first.status_code == 200, first.text
            assert client.post(send, headers=owner, json=data).json() == first.json()
            assert client.get(detail_url, headers=other).status_code == 404
            assert client.get(detail_url, headers={"Authorization": "Bearer admin-a@other"}).status_code == 404
            assert client.get(detail_url, headers={**owner, "X-Admin-Chat-Owner": "https://realm.test#admin-b"}).status_code == 409
            assert client.delete(detail_url, headers=other).status_code == 404
            assert client.get(path, headers=other).json() == []
            history = client.get(detail_url, headers=owner).json()["messages"]
            assert [msg["content"] for msg in history] == ["Xem số liệu", "Test reply"]
            assert history[1]["tools_used"] == ["get_kpis"]
            second = {**data, "request_id": str(uuid4()), "message": "Còn gì nữa?", "history": [{"role": "user", "content": "client"}]}
            assert client.post(send, headers=owner, json=second).status_code == 200
            assert [msg["content"] for msg in calls[1]] == ["Xem số liệu", "Test reply"]
            assert client.patch(detail_url, headers=owner, json={"is_pinned": True}).json()["is_pinned"]
            assert client.delete(path, headers=owner).status_code == 200
            assert client.post(send, headers=owner, json=data).status_code == 404
            assert mongo[database].organizer_conversations.count_documents({}) == 0
        finally:
            mongo.drop_database(database)
