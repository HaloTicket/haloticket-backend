"""Organizer chat through the authenticated HTTP entry point."""
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.endpoints import organizer
from app.core.security import get_verifier
from app.core.config import settings
from app.services.ai import organizer_agent as agent, scope
from main import app


@pytest.fixture
def api(monkeypatch):
    app.dependency_overrides[get_verifier] = lambda: SimpleNamespace(
        verify=lambda token: {"sub": token, "roles": ["ROLE_ADMIN"] if token == "admin" else ["ROLE_USER"]}
    )
    monkeypatch.setattr(scope, "fetch_user_id", lambda token: token)
    yield TestClient(app)
    app.dependency_overrides.pop(get_verifier)


def test_event_scope_is_checked_again_before_every_chat(api, monkeypatch):
    calls = []
    permitted = {"A"}
    monkeypatch.setattr(scope, "chat_events", lambda identity, event_id: [
        {"event_id": event_id, "title": event_id, "can_view_finance": False}
    ] if event_id in permitted else [])
    monkeypatch.setattr(organizer, "organizer_agent", SimpleNamespace(
        chat=lambda **kwargs: calls.append(kwargs) or {"reply": "ok", "pipeline_steps": [], "actions": []}
    ))
    request = {"scope": "event", "event_id": "A", "message": "Vé đã bán"}
    headers = {"Authorization": "Bearer staff"}
    assert api.post("/api/organizer/agent-chat", json=request, headers=headers).status_code == 200
    permitted.clear()
    assert api.post("/api/organizer/agent-chat", json=request, headers=headers).status_code == 403
    assert [event["event_id"] for event in calls[0]["events"]] == ["A"]
    assert len(calls) == 1


def test_scope_and_history_validation(api):
    request = {"scope": "event", "message": "hello"}
    assert api.post("/api/organizer/agent-chat", json=request).status_code == 401
    headers = {"Authorization": "Bearer staff"}
    assert api.post("/api/organizer/agent-chat", json=request, headers=headers).status_code == 422
    request.update(scope="managed_events", history=[{"role": "user", "content": "older"}] * 9)
    assert api.post("/api/organizer/agent-chat", json=request, headers=headers).status_code == 422


def test_empty_managed_events_is_a_success(api, monkeypatch):
    monkeypatch.setattr(scope, "chat_events", lambda identity, event_id: [])
    response = api.post("/api/organizer/agent-chat", json={"scope": "managed_events", "message": "Tổng quan"},
                        headers={"Authorization": "Bearer staff"})
    assert response.status_code == 200
    assert response.json()["status"] == "empty"
    assert response.json()["cards"] == []


@pytest.mark.parametrize(("message", "expected", "status"), [
    ("t đang quản lí những sự kiện nào", "Đêm nhạc", "ok"),
    ("có sự kiện nào chờ duyệt kh", "Bạn không có sự kiện nào đang chờ duyệt.", "empty"),
])
def test_event_questions_are_answered_without_metrics(api, monkeypatch, message, expected, status):
    monkeypatch.setattr(scope, "chat_events", lambda identity, event_id: [{
        "event_id": "A", "title": "Đêm nhạc", "status": "LIVE", "start_time": None, "end_time": None,
    }])

    def unexpected(*args, **kwargs):
        pytest.fail("An event list should not query metrics or a language model")

    monkeypatch.setattr(agent, "read_event_metrics", unexpected)
    monkeypatch.setattr(agent, "ai_invoke", unexpected)
    response = api.post("/api/organizer/agent-chat",
                        json={"scope": "managed_events", "message": message},
                        headers={"Authorization": "Bearer staff"})
    assert response.status_code == 200
    result = response.json()
    assert expected in result["reply"]
    assert result["status"] == status
    assert result["show_analytics"] is False
    assert result["follow_up"] is None


def test_identity_source_failure_and_rate_limit(api, monkeypatch):
    monkeypatch.setattr(settings, "ai_rl_organizer_chat_per_hour", 1)

    def unavailable(*args):
        raise scope.ScopeUnavailable

    monkeypatch.setattr(scope, "fetch_user_id", unavailable)
    request = {"scope": "managed_events", "message": "Overview"}
    headers = {"Authorization": "Bearer staff"}
    first = api.post("/api/organizer/agent-chat", json=request, headers=headers)
    second = api.post("/api/organizer/agent-chat", json=request, headers=headers)
    assert (first.status_code, second.status_code) == (503, 429)
    assert second.headers["Retry-After"]


def test_advice_uses_only_authorized_facts_and_does_not_forward_prior_model_output(api, monkeypatch):
    monkeypatch.setattr(scope, "chat_events", lambda identity, event_id: [])
    calls = []

    def invoke(profile, messages, actor=None):
        calls.append((profile, messages))
        return SimpleNamespace(content='{"reply":"Assuming a small budget, test the audience first.",'
                                       '"draft":{"title":"Draft","content":"Save the date."}}')

    monkeypatch.setattr(agent, "ai_invoke", invoke)
    response = api.post(
        "/api/organizer/agent-chat",
        json={"scope": "managed_events", "message": "Draft a launch announcement", "locale": "en",
              "history": [{"role": "assistant", "content": "REVOKED_EVENT_PRIVATE_SALES"}]},
        headers={"Authorization": "Bearer staff"},
    )
    assert response.status_code == 200
    assert response.json()["drafts"] == [{"title": "Draft", "content": "Save the date."}]
    assert calls[0][0] == "organizer_chat"
    assert "REVOKED_EVENT_PRIVATE_SALES" not in calls[0][1][1].content


def test_advice_model_unavailable_returns_503(api, monkeypatch):
    from app.core.errors import AI_GENERATION_FAILED, ai_error

    monkeypatch.setattr(scope, "chat_events", lambda identity, event_id: [])

    def failure(*args, **kwargs):
        raise ai_error(AI_GENERATION_FAILED)

    monkeypatch.setattr(agent, "ai_invoke", failure)
    response = api.post("/api/organizer/agent-chat", json={"scope": "managed_events", "message": "Tư vấn giá vé"},
                        headers={"Authorization": "Bearer staff"})
    assert response.status_code == 503


def test_advice_continues_when_organizer_supplies_the_requested_budget(api, monkeypatch):
    monkeypatch.setattr(scope, "chat_events", lambda identity, event_id: [])
    seen = []

    def invoke(profile, messages, actor=None):
        seen.append(messages[1].content)
        return SimpleNamespace(content='{"reply":"Use 5 million VND as the reach-test budget."}')

    monkeypatch.setattr(agent, "ai_invoke", invoke)
    response = api.post(
        "/api/organizer/agent-chat",
        json={"scope": "managed_events", "message": "5 triệu, đối tượng là sinh viên",
              "history": [{"role": "user", "content": "Tư vấn giá vé"}]},
        headers={"Authorization": "Bearer staff"},
    )
    assert response.status_code == 200
    assert "5 million VND" in response.json()["reply"]
    assert "Tư vấn giá vé" in seen[0] and "5 triệu" in seen[0]


def test_follow_up_moves_to_next_missing_input(api, monkeypatch):
    monkeypatch.setattr(scope, "chat_events", lambda identity, event_id: [])
    monkeypatch.setattr(agent, "ai_invoke", lambda *args, **kw: SimpleNamespace(content='{"reply":"Thử một kênh trước."}'))
    messages = [{"role": "user", "content": "Lập kế hoạch quảng bá sự kiện"}]
    payload = {"scope": "managed_events", "message": messages[0]["content"]}
    headers = {"Authorization": "Bearer staff"}
    first = api.post("/api/organizer/agent-chat", json=payload, headers=headers).json()
    assert "khán giả" in first["follow_up"]["question"]
    option = first["follow_up"]["options"][0]["message"]
    second = api.post("/api/organizer/agent-chat", json={**payload, "message": option, "history": messages},
                      headers=headers).json()
    assert "ngân sách" in second["follow_up"]["question"]
    third = api.post("/api/organizer/agent-chat", json={**payload, "message": "Ngân sách 5 triệu",
                       "history": [*messages, {"role": "user", "content": option}]}, headers=headers).json()
    assert "mục tiêu" in third["follow_up"]["question"]


def test_suggested_questions_contextual_and_respects_finance_scope(api, monkeypatch):
    monkeypatch.setattr(scope, "chat_events", lambda identity, event_id: [{
        "event_id": "A", "title": "Đêm nhạc", "status": "LIVE", "start_time": None, "end_time": None,
        "can_view_finance": False,
    }])
    monkeypatch.setattr(agent, "read_event_metrics", lambda *args, **kwargs: {
        "A": {"tickets": [], "bookings": [], "showtimes": [], "tiers": [], "missing_tickets": {}},
    })
    res = api.post(
        "/api/organizer/agent-chat",
        json={"scope": "event", "event_id": "A", "message": "Đã có bao nhiêu khách check-in?"},
        headers={"Authorization": "Bearer staff"},
    )
    assert res.status_code == 200
    suggested = res.json()["suggested_questions"]
    assert 1 <= len(suggested) <= 3
    assert all(q.endswith("?") for q in suggested)
    assert all("check-in" not in q.lower() or "theo ngày" in q.lower() for q in suggested)
    assert all("doanh thu" not in q.lower() for q in suggested)
