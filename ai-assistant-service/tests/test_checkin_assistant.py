import json
from types import SimpleNamespace

import httpx
from fastapi.testclient import TestClient

from app.core.security import get_verifier
from app.services.ai import ai_call
from main import app


URL = "/api/organizer/checkin-chat"
HEADERS = {"Authorization": "Bearer staff-checkin-test"}


def test_checkin_assistant_uses_authorized_showtime_aggregates(monkeypatch):
    monkeypatch.setattr(app, "dependency_overrides", {
        get_verifier: lambda: SimpleNamespace(verify=lambda token: {"sub": token}),
    })
    requests = []
    model_stats = []

    def summary(url, **kwargs):
        requests.append((url, kwargs["headers"]["Authorization"]))
        if url.endswith("/api/events/event-1"):
            return httpx.Response(200, json={"id": "event-1", "showtimes": [{"id": "empty"}]})
        return httpx.Response(200, json={
            "eventId": "event-1", "total": 9999, "checkedIn": 8888,
            "byShowtime": [
                {"id": "show-1", "name": "Ca tối", "total": 27, "checkedIn": 10, "email": "private@example.com"},
                {"id": "show-2", "name": "Ca chiều", "total": 9999, "checkedIn": 8888},
                {"id": None, "name": None, "total": 2, "checkedIn": 1},
            ],
            "byTicketType": [{"name": "Hạng khác", "total": 9999, "checkedIn": 8888}],
        })

    def model(profile, prompts, **kwargs):
        model_stats.append(json.loads(prompts[-1].content.splitlines()[1]))
        assert kwargs.get("tools") is None
        return SimpleNamespace(content="Đã vào 10 vé.")

    monkeypatch.setattr(httpx, "get", summary)
    monkeypatch.setattr(ai_call, "ai_invoke", model)
    request = {"event_id": "event-1", "showtime_id": "show-1", "message": "Đã vào bao nhiêu?"}
    client = TestClient(app)
    assert client.post(URL, json=request).status_code == 401
    response = client.post(URL, json=request, headers=HEADERS)
    assert response.status_code == 200, response.text
    assert response.json() == {"reply": "Đã vào 10 vé."}
    assert response.headers["Cache-Control"] == "private, no-store"
    assert len(requests) == 1
    assert requests[0][0].endswith("/api/tickets/checkin/events/event-1/summary")
    assert requests[0][1] == HEADERS["Authorization"]
    assert model_stats[-1] == {
        "scope": "selected showtime", "available": True, "name": "Ca tối",
        "total": 27, "checkedIn": 10, "remaining": 17,
    }

    assert client.post(URL, json={**request, "showtime_id": "empty"}, headers=HEADERS).status_code == 200
    assert model_stats[-1]["available"] and model_stats[-1]["total"] == 0

    assert client.post(URL, json={**request, "showtime_id": "unknown"}, headers=HEADERS).status_code == 200
    assert model_stats[-1] == {"scope": "selected showtime", "available": False}

    assert client.post(URL, json={**request, "showtime_id": None}, headers=HEADERS).status_code == 200
    assert model_stats[-1]["total"] == 9999
    assert [row["showtimeId"] for row in model_stats[-1]["byShowtime"]][:2] == ["show-1", "show-2"]

    monkeypatch.setattr(httpx, "get", lambda *args, **kwargs: httpx.Response(403))
    model_stats.clear()
    assert client.post(URL, json=request, headers=HEADERS).status_code == 403
    assert not model_stats


def test_general_checkin_chat_uses_only_assigned_events_and_authorized_totals(monkeypatch):
    monkeypatch.setattr(app, "dependency_overrides", {
        get_verifier: lambda: SimpleNamespace(verify=lambda token: {"sub": token}),
    })
    visited = []

    def gateway(url, **kwargs):
        assert kwargs["headers"]["Authorization"] == HEADERS["Authorization"]
        path = url.split("/api/", 1)[1]
        visited.append(path)
        if path == "events/my-events":
            return httpx.Response(200, json=[
                {"id": "assigned", "title": "Đêm nhạc", "staffEmail": "personal@example.com"},
                {"id": "view-only", "title": "Hội sách"},
                {"id": "../invalid", "title": "Invalid"},
            ])
        if path.endswith("/assigned/summary"):
            return httpx.Response(200, json={
                "eventId": "assigned", "total": 40, "checkedIn": 20, "byShowtime": [], "byTicketType": [],
            })
        return httpx.Response(403)

    def model(profile, prompts, **kwargs):
        data = json.loads(prompts[-1].content.splitlines()[1])
        assert data["scope"] == "assigned events"
        assert data["events"] == [
            {"title": "Đêm nhạc", "total": 40, "checkedIn": 20, "remaining": 20},
            {"title": "Hội sách"},
        ]
        return SimpleNamespace(content="Bạn được phân công hai sự kiện.")

    monkeypatch.setattr(httpx, "get", gateway)
    monkeypatch.setattr(ai_call, "ai_invoke", model)
    client = TestClient(app)
    body = {"message": "Tôi được phân công sự kiện nào?"}
    assert client.post(URL, json=body).status_code == 401
    assert client.post(URL, headers=HEADERS, json={**body, "showtime_id": "unscoped"}).status_code == 422
    response = client.post(URL, headers=HEADERS, json=body)
    assert response.status_code == 200, response.text
    assert response.json()["reply"] == "Bạn được phân công hai sự kiện."
    assert visited[0] == "events/my-events"
    assert set(visited[1:]) == {"tickets/checkin/events/assigned/summary", "tickets/checkin/events/view-only/summary"}
