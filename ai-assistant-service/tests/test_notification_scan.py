from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.endpoints import notification_scan
from app.core.config import settings


def client():
    app = FastAPI()
    app.include_router(notification_scan.router)
    return TestClient(app)


def test_scans_submitted_notification_without_an_event_lookup(monkeypatch):
    monkeypatch.setattr(settings, "authz_event_scope_internal_token", "test-token")
    endpoint = "/internal/notification-content-scan"
    headers = {"X-Internal-Token": "test-token"}
    clean = client().post(endpoint, headers=headers, json={
        "title": "Cập nhật check-in", "message": "Khu vực check-in mở lúc 18 giờ.",
    })
    assert clean.status_code == 200
    assert clean.json() == {"status": "COMPLETE", "level": "LOW", "score": 0, "findings": []}

    flagged = client().post(endpoint, headers=headers, json={
        "title": "Ưu đãi", "message": "Bán vũ khí quân dụng giá rẻ",
    })
    assert flagged.status_code == 200
    assert flagged.json()["level"] == "HIGH"
    assert flagged.json()["score"] > 0
    assert flagged.json()["findings"]


def test_requires_a_configured_internal_token(monkeypatch):
    endpoint = "/internal/notification-content-scan"
    payload = {"title": "Thông báo", "message": "Nội dung"}
    monkeypatch.setattr(settings, "authz_event_scope_internal_token", "test-token")
    assert client().post(endpoint, json=payload).status_code == 403
    monkeypatch.setattr(settings, "authz_event_scope_internal_token", "")
    assert client().post(endpoint, json=payload).status_code == 503


def test_reports_unavailable_when_rules_cannot_be_loaded(monkeypatch):
    monkeypatch.setattr(settings, "authz_event_scope_internal_token", "test-token")
    monkeypatch.setattr(notification_scan, "load_rules", lambda: None)
    response = client().post("/internal/notification-content-scan",
                             headers={"X-Internal-Token": "test-token"},
                             json={"title": "Thông báo", "message": "Nội dung"})
    assert response.json() == {"status": "UNAVAILABLE"}
