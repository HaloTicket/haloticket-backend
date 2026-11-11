"""Risk routes end to end with in-memory storage, a fake event service and a fake model: no database, no network."""
import json

import pytest

from app.core.config import settings
from app.core.errors import ai_error, AI_UNAVAILABLE
from app.services.admin_intel import risk_service, upstream
from tests.admin_fakes import FakeModel, FakeStore, FakeUpstream, admin_token, event, make_client

CLEAN = event("e1")
PHONE = event("e2", description="Bán vũ khí quân dụng giá rẻ, liên hệ 0901234567")
BODY = {"event_id": "e1", "title": "x", "description": "y", "organizer_name": "z"}


@pytest.fixture
def world(monkeypatch):
    up = FakeUpstream(monkeypatch, [CLEAN, PHONE])
    st = FakeStore(monkeypatch)
    model = FakeModel('{"flags": []}')
    monkeypatch.setattr(risk_service, "ai_invoke", model)
    return up, st, model, make_client()


def post(client, body=None, **over):
    return client.post("/api/admin/risk-scoring", json={**BODY, **(body or {}), **over})


def test_a_clean_event_gets_all_three_layers_and_the_old_fields(world):
    _, _, model, client = world
    response = post(client)
    assert response.status_code == 200
    body = response.json()
    assert body["risk_score"] == 0 and body["risk_level"] == "LOW" and body["reasons"] == []  # old contract
    assert body["coverage"]["ai"] == "ran" and body["complete"] is True and body["ruleset_version"] == "4"
    assert body["stored"] is True and body["cached"] is False and body["assessment_id"]
    assert model.calls[0]["profile"] == "risk_scoring" and model.calls[0]["actor"].startswith("sub:")


def test_the_event_is_read_from_the_event_service_not_from_the_client(world):
    _, _, _, client = world
    body = post(client, event_id="e2", description="văn bản sạch do client gửi").json()
    assert body["risk_level"] != "LOW" or body["risk_score"] > 0
    assert any(r["code"] == "prohibited_goods" for r in body["reason_items"])  # found in the stored event, not in the client text


def test_a_stored_score_for_the_same_content_is_returned_without_a_model_call(world):
    _, st, model, client = world
    first = post(client).json()
    second = post(client).json()
    assert second["cached"] is True and second["assessment_id"] == first["assessment_id"] and len(model.calls) == 1
    assert len(st.assessments) == 1


def test_changed_content_or_refresh_scores_again(world, monkeypatch):
    up, st, model, client = world
    post(client)
    post(client, refresh=True)
    assert len(model.calls) == 2 and len(st.assessments) == 2
    model.answers.append('{"flags": []}')
    up.values["approvals"] = [{**CLEAN, "description": "mô tả mới", "descriptionText": "mô tả mới"}, PHONE]
    upstream.reset_for_tests()
    assert post(client).json()["cached"] is False and len(st.assessments) == 3


def test_an_ai_failure_still_scores_and_says_so(world, monkeypatch):
    _, _, _, client = world
    monkeypatch.setattr(risk_service, "ai_invoke", FakeModel(ai_error(AI_UNAVAILABLE)))
    body = post(client, event_id="e2").json()
    assert body["coverage"]["ai"] == "failed" and body["complete"] is False
    assert body["risk_score"] >= 60 and body["risk_level"] in ("MEDIUM", "HIGH", "LOW")
    assert any(r["code"] == "prohibited_goods" for r in body["reason_items"])


def test_bad_model_output_is_a_failed_layer_not_a_zero(world, monkeypatch):
    _, _, _, client = world
    monkeypatch.setattr(risk_service, "ai_invoke", FakeModel('{"risk_score": 0}'))
    assert post(client).json()["coverage"]["ai"] == "failed"


def test_a_model_flag_adds_points_only_for_a_real_quote(world, monkeypatch):
    _, _, _, client = world
    quote = "Bán vũ khí quân dụng giá rẻ"
    monkeypatch.setattr(risk_service, "ai_invoke", FakeModel(json.dumps({"flags": [
        {"code": "SCAM_PATTERN", "quote": quote, "confidence": "HIGH"}, {"code": "SCAM_PATTERN", "quote": "invented", "confidence": "HIGH"}]})))
    items = post(client, event_id="e2").json()["reason_items"]
    ai = [r for r in items if r["layer"] == "AI"]
    assert len(ai) == 1 and ai[0]["evidence"][0]["text"] == quote


def test_unreadable_event_uses_the_client_text_and_is_marked(world):
    up, _, _, client = world
    up.values["approvals"] = RuntimeError("gateway down")
    body = post(client, description="Bán vũ khí quân dụng giá rẻ").json()
    assert body["complete"] is False and "UNAVAILABLE:event_fetch" in body["coverage"]["signals"]["missing"]
    assert any(r["code"] == "prohibited_goods" for r in body["reason_items"])


def test_without_storage_the_score_is_still_returned(monkeypatch):
    FakeUpstream(monkeypatch, [CLEAN])
    monkeypatch.setattr(risk_service, "ai_invoke", FakeModel('{"flags": []}'))
    monkeypatch.setattr(settings, "ai_admin_database_url", "")
    body = post(make_client()).json()
    assert body["stored"] is False and body["risk_level"] == "LOW"


def test_refresh_is_rate_limited_but_a_cached_answer_is_free(world, monkeypatch):
    _, _, _, client = world
    monkeypatch.setattr(settings, "ai_rl_risk_scoring_per_hour", 2)
    monkeypatch.setattr(risk_service, "ai_invoke", FakeModel(*(['{"flags": []}'] * 10)))
    assert post(client).status_code == 200
    assert [post(client).status_code for _ in range(5)] == [200] * 5  # same content: stored, no model call, no limit
    assert post(client, refresh=True).status_code == 200
    limited = post(client, refresh=True)
    assert limited.status_code == 429 and limited.json()["code"] == "AI_RATE_LIMITED"


def test_the_event_id_is_validated(world):
    _, _, _, client = world
    assert client.post("/api/admin/risk-scoring", json={"event_id": "  "}).status_code == 422
    assert client.post("/api/admin/risk-scoring", json={"event_id": "x" * 101}).status_code == 422


# review queue
def test_batch_scores_without_a_model_call_and_lists_missing(world):
    _, st, model, client = world
    response = client.post("/api/admin/risk/batch", json={"event_ids": ["e1", "e2", "nope"]})
    body = response.json()
    assert response.status_code == 200 and not model.calls
    assert set(body["assessments"]) == {"e1", "e2"} and body["missing"] == ["nope"]
    assert body["assessments"]["e2"]["risk_score"] > body["assessments"]["e1"]["risk_score"]
    assert body["assessments"]["e1"]["ai_layer"] == "skipped" and body["assessments"]["e1"]["complete"] is False
    assert {a["trigger"] for a in st.assessments} == {"queue"}


def test_batch_reuses_stored_scores_and_ai_runs_later_on_the_same_content(world):
    _, st, model, client = world
    client.post("/api/admin/risk/batch", json={"event_ids": ["e1"]})
    client.post("/api/admin/risk/batch", json={"event_ids": ["e1"]})
    assert len(st.assessments) == 1
    detail = post(client).json()  # opening the detail: the AI layer was never run for this content, so it runs now
    assert detail["coverage"]["ai"] == "ran" and len(model.calls) == 1


def test_batch_when_the_event_service_is_down_is_a_502_with_a_code(world):
    up, _, _, client = world
    up.values["approvals"] = RuntimeError("down")
    response = client.post("/api/admin/risk/batch", json={"event_ids": ["e1"]})
    assert response.status_code == 502 and response.json()["detail"]["code"] == "UPSTREAM_UNAVAILABLE"


# decisions and agreement
def test_a_decision_is_recorded_only_when_the_real_status_matches(world):
    up, st, _, client = world
    up.values["approvals"] = [event("e1", status="APPROVED")]
    mismatch = client.post("/api/admin/risk/decisions", json={"event_id": "e1", "decision": "REJECTED", "reason": "x"})
    assert mismatch.status_code == 409 and mismatch.json()["detail"]["code"] == "DECISION_MISMATCH"
    ok = client.post("/api/admin/risk/decisions", json={"event_id": "e1", "decision": "APPROVED"})
    assert ok.status_code == 200 and st.decisions[0]["decided_by"] == "admin-1" and st.decisions[0]["source"] == "web"
    assert client.post("/api/admin/risk/decisions", json={"event_id": "e1", "decision": "MAYBE"}).status_code == 422


def test_decisions_need_storage(monkeypatch):
    FakeUpstream(monkeypatch, [event("e1", status="APPROVED")])
    monkeypatch.setattr(settings, "ai_admin_database_url", "")
    response = make_client().post("/api/admin/risk/decisions", json={"event_id": "e1", "decision": "APPROVED"})
    assert response.status_code == 503 and response.json()["code"] == "AI_DB_NOT_CONFIGURED"


def test_agreement_counts_recorded_and_derived_decisions(world):
    up, st, _, client = world
    events = [event(f"h{i}", description="Bán vũ khí quân dụng giá rẻ") for i in range(2)] + [event(f"l{i}") for i in range(2)] + [event("m", description="Tài trợ bởi thuốc lá điện tử XYZ")]
    up.values["approvals"] = events
    client.post("/api/admin/risk/batch", json={"event_ids": [e["id"] for e in events]})
    # h0 rejected (agree, recorded), h1 approved (disagree, derived from status), l0 approved (agree, derived), l1 still pending, m is medium
    up.values["approvals"] = [{**events[0], "eventApprovalStatus": "REJECTED"}, {**events[1], "eventApprovalStatus": "APPROVED"},
                              {**events[2], "eventApprovalStatus": "APPROVED"}, events[3], {**events[4], "eventApprovalStatus": "APPROVED"}]
    st.decisions.append({"event_id": "h0", "decision": "REJECTED", "reason": "", "decided_by": "admin-1", "source": "web"})
    body = client.get("/api/admin/risk/agreement?days=30").json()
    assert body["decided"] == 4 and body["pending"] == 1
    assert body["agree"] == 2 and body["disagree"] == 1 and body["neutral"] == 1 and body["derived"] == 3
    assert body["by_level"]["HIGH"] == {"APPROVED": 1, "REJECTED": 1}


def test_the_new_routes_are_admin_only(world):
    _, _, _, _ = world
    anonymous = make_client()
    anonymous.headers.pop("Authorization")
    customer = make_client(roles=("ROLE_USER",))
    for method, path, body in [("POST", "/api/admin/risk/batch", {"event_ids": []}), ("POST", "/api/admin/risk/decisions", {"event_id": "e", "decision": "APPROVED"}),
                               ("GET", "/api/admin/risk/agreement", None), ("GET", "/api/admin/dashboard/kpis", None), ("GET", "/api/admin/attention", None),
                               ("POST", "/api/admin/dashboard/summary", {}), ("GET", "/api/admin/pins", None), ("POST", "/api/admin/pins", {"title": "t", "sql": "select 1"}),
                               ("DELETE", "/api/admin/pins/x", None), ("GET", "/api/admin/pins/x/data", None)]:
        assert anonymous.request(method, path, json=body).status_code == 401, path
        assert customer.request(method, path, json=body).status_code == 403, path
