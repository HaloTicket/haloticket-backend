"""Token verification on the admin and analysis routes, and what the admin routes do once the caller is allowed in."""
import time
from types import SimpleNamespace

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.endpoints import admin, customer, organizer
from app.core import security
from app.core.config import settings
from app.core.security import TokenVerifier, get_verifier

ISSUER = "https://login.example.test/realms/haloticket"


def _key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


SIGNING_KEY = _key()
OTHER_KEY = _key()


def pem(key):
    return key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())


def token(roles=("ROLE_USER",), key=SIGNING_KEY, issuer=ISSUER, expires_in=300, **extra):
    claims = {"iss": issuer, "sub": "11111111-2222-3333-4444-555555555555", "roles": list(roles),
              "realm_access": {"roles": list(roles)}, "exp": int(time.time()) + expires_in, **extra}
    return jwt.encode(claims, pem(key), algorithm="RS256")


def auth(value):
    return {"Authorization": f"Bearer {value}"}


@pytest.fixture
def calls():
    return []


@pytest.fixture
def client(monkeypatch, calls, tmp_path):
    verifier = TokenVerifier(ISSUER, lambda _token: SIGNING_KEY.public_key())

    def analyze(question, scope=None):
        calls.append(("data-analysis", question))
        return {"question": question, "reply": "ok"}

    monkeypatch.setattr(organizer, "sql_engine", SimpleNamespace(analyze_data=analyze))
    monkeypatch.setattr(organizer, "organizer_agent", SimpleNamespace(
        chat=lambda **kwargs: calls.append(("agent-chat",)) or {"reply": "ok"},
    ))
    monkeypatch.setattr(organizer, "event_content", SimpleNamespace(
        generate_event_content=lambda title, points, actor=None: calls.append(("generate",)) or {"description": "d", "terms_and_conditions": "t"},
    ))
    monkeypatch.setattr(customer.booking_agent, "chat", lambda m, h, db: {"reply": "ok", "system_actions": []})
    log_file = tmp_path / "event-error.log"
    log_file.write_text("ERROR payment failed for user a@example.com token=abc123secret\nINFO fine\n", encoding="utf-8")
    monkeypatch.setattr(settings, "ai_system_log_file", str(log_file))

    app = FastAPI()
    app.include_router(customer.router, prefix="/api/customer")
    app.include_router(organizer.router, prefix="/api/organizer")
    app.include_router(admin.router, prefix="/api/admin")
    app.dependency_overrides[get_verifier] = lambda: verifier
    app.dependency_overrides[customer.get_db] = lambda: SimpleNamespace()
    return TestClient(app)


# the routes that run the unscoped organizer agent, or read the server: ADMIN only
ADMIN_ROUTES = [
    ("POST", "/api/organizer/agent-chat", {"message": "hi"}),
    ("GET", "/api/admin/system-logs", None),
    ("GET", "/api/admin/ssl-status", None),
    ("GET", "/api/admin/system-health", None),
    ("POST", "/api/admin/risk-scoring", {"event_id": "e", "title": "t", "description": "d", "organizer_name": "o"}),
    ("POST", "/api/admin/assistant-chat", {"message": "hi"}),
]


def send(client, method, path, body, headers=None):
    return client.request(method, path, json=body, headers=headers or {})


@pytest.mark.parametrize("method,path,body", ADMIN_ROUTES)
def test_admin_routes_refuse_anonymous_and_customers(client, calls, method, path, body):
    anonymous = send(client, method, path, body)
    assert anonymous.status_code == 401
    assert anonymous.json()["detail"]["code"] == "UNAUTHENTICATED"
    assert anonymous.headers["www-authenticate"] == "Bearer"

    customer_response = send(client, method, path, body, auth(token(["ROLE_USER"])))
    assert customer_response.status_code == 403
    assert customer_response.json()["detail"]["code"] == "ADMIN_ONLY"
    assert calls == []  # the handler never ran


@pytest.mark.parametrize("roles", [["ROLE_ORGANIZER"], ["ROLE_STAFF"], ["ROLE_USER", "ROLE_ORGANIZER", "ROLE_STAFF"], ["ADMINISTRATOR"]])
def test_other_roles_are_not_admin(client, roles):
    assert send(client, "POST", "/api/organizer/agent-chat", {"message": "q"}, auth(token(roles))).status_code == 403


@pytest.mark.parametrize("claims", [{"roles": ["ROLE_ADMIN"]}, {"realm_access": {"roles": ["ROLE_ADMIN"]}}, {"roles": ["admin"]}])
def test_the_admin_role_is_read_from_either_claim(client, calls, claims):
    value = jwt.encode({"iss": ISSUER, "sub": "s", "exp": int(time.time()) + 300, **claims}, pem(SIGNING_KEY), algorithm="RS256")
    response = send(client, "POST", "/api/organizer/data-analysis", {"question": "Đếm số sự kiện"}, auth(value))
    assert response.status_code == 200
    assert calls == [("data-analysis", "Đếm số sự kiện")]


def test_an_admin_reaches_every_admin_route(client, calls):
    admin_token = auth(token(["ROLE_USER", "ROLE_ADMIN"]))
    assert send(client, "POST", "/api/organizer/data-analysis", {"question": "q"}, admin_token).status_code == 200
    assert send(client, "POST", "/api/organizer/voucher-strategy", {"event_id": "e1"}, admin_token).status_code == 200
    assert send(client, "POST", "/api/organizer/agent-chat", {"message": "hi"}, admin_token).status_code == 200
    assert send(client, "GET", "/api/admin/system-logs", None, admin_token).status_code == 200
    assert [call[0] for call in calls][:3] == ["data-analysis", "data-analysis", "agent-chat"]  # voucher-strategy asks the engine


@pytest.mark.parametrize("path,body", [("/api/organizer/data-analysis", {"question": "q"}), ("/api/organizer/voucher-strategy", {"event_id": "e1"})])
def test_the_analysis_routes_need_a_login(client, calls, path, body):
    assert send(client, "POST", path, body).status_code == 401
    assert calls == []


def test_content_generation_needs_a_login_but_not_a_role(client, calls):
    body = {"title": "t", "bullet_points": "b"}
    assert send(client, "POST", "/api/organizer/generate-event-content", body).status_code == 401
    assert send(client, "POST", "/api/organizer/generate-event-content", body, auth(token(["ROLE_USER"]))).status_code == 200
    assert ("generate",) in calls


def test_the_public_chatbot_routes_need_no_token(client):
    assert client.post("/api/customer/smart-booking-chat", json={"message": "xin chào"}).status_code == 200


# --- what a bad token looks like -----------------------------------------------------------------------------------------

@pytest.mark.parametrize("label,value", [
    ("expired", lambda: token(["ROLE_ADMIN"], expires_in=-3600)),
    ("signed by another key", lambda: token(["ROLE_ADMIN"], key=OTHER_KEY)),
    ("wrong issuer", lambda: token(["ROLE_ADMIN"], issuer="https://evil.example.test/realms/x")),
    ("no expiry", lambda: jwt.encode({"iss": ISSUER, "sub": "s", "roles": ["ROLE_ADMIN"]}, pem(SIGNING_KEY), algorithm="RS256")),
    ("alg none", lambda: jwt.encode({"iss": ISSUER, "sub": "s", "roles": ["ROLE_ADMIN"], "exp": int(time.time()) + 300}, None, algorithm="none")),
    ("symmetric algorithm", lambda: jwt.encode({"iss": ISSUER, "sub": "s", "roles": ["ROLE_ADMIN"], "exp": int(time.time()) + 300}, "x" * 64, algorithm="HS256")),
    ("garbage", lambda: "not-a-jwt"),
])
def test_tokens_that_do_not_check_out_are_rejected(client, calls, label, value):
    response = send(client, "POST", "/api/organizer/data-analysis", {"question": "q"}, auth(value()))
    assert response.status_code == 401, label
    assert response.json()["detail"]["code"] == "INVALID_TOKEN"
    assert calls == []


def test_a_header_that_is_not_a_bearer_token_is_unauthenticated(client):
    for headers in ({"Authorization": "Basic abc"}, {"Authorization": "Bearer"}, {"Authorization": "Bearer   "}):
        assert send(client, "GET", "/api/admin/system-logs", None, headers).status_code == 401


def test_the_audience_is_checked_only_when_one_is_configured():
    with_audience = TokenVerifier(ISSUER, lambda _t: SIGNING_KEY.public_key(), audience="ai-assistant")
    without = TokenVerifier(ISSUER, lambda _t: SIGNING_KEY.public_key())
    value = token(aud="api-gateway")
    assert without.verify(value)["aud"] == "api-gateway"
    with pytest.raises(jwt.InvalidAudienceError):
        with_audience.verify(value)
    assert with_audience.verify(token(aud="ai-assistant"))["aud"] == "ai-assistant"


def test_when_keycloak_cannot_be_reached_for_the_keys_the_request_is_refused_not_let_through(monkeypatch):
    def unreachable(_token):
        raise jwt.PyJWKClientConnectionError("down")

    app = FastAPI()
    app.include_router(admin.router, prefix="/api/admin")
    app.dependency_overrides[get_verifier] = lambda: TokenVerifier(ISSUER, unreachable)
    response = TestClient(app).get("/api/admin/system-logs", headers=auth(token(["ROLE_ADMIN"])))
    assert response.status_code == 401


def test_without_keycloak_settings_the_protected_routes_refuse_everyone(monkeypatch):
    monkeypatch.setattr(settings, "keycloak_issuer_uri", "")
    monkeypatch.setattr(settings, "keycloak_jwk_set_uri", "")
    security.get_verifier.cache_clear()
    try:
        app = FastAPI()
        app.include_router(admin.router, prefix="/api/admin")
        response = TestClient(app).get("/api/admin/system-logs", headers=auth(token(["ROLE_ADMIN"])))
        assert response.status_code == 503
        assert response.json()["detail"]["code"] == "AUTH_NOT_CONFIGURED"
    finally:
        security.get_verifier.cache_clear()


# --- ssl-status and system-logs ------------------------------------------------------------------------------------------

@pytest.fixture
def admin_client(client, monkeypatch):
    monkeypatch.setattr(settings, "alert_ssl_domains", "haloticket.duckdns.org, haloticket-web.pages.dev")
    client.headers.update(auth(token(["ROLE_USER", "ROLE_ADMIN"])))
    return client


@pytest.mark.parametrize("domain", ["example.com", "169.254.169.254", "127.0.0.1", "localhost", "10.0.0.5", "evil.test:443",
                                     "https://internal.service.local/path", "haloticket.duckdns.org.evil.test"])
def test_ssl_status_refuses_a_domain_that_is_not_the_systems_own_and_never_connects(admin_client, monkeypatch, domain):
    monkeypatch.setattr(admin.socket, "create_connection", lambda *a, **k: pytest.fail("connected to a domain that is not allowed"))
    response = admin_client.get("/api/admin/ssl-status", params={"domain": domain})
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "DOMAIN_NOT_ALLOWED"


@pytest.mark.parametrize("domain", [None, "haloticket.duckdns.org", "HALOTICKET-WEB.PAGES.DEV", "https://haloticket.duckdns.org/some/path"])
def test_ssl_status_checks_the_systems_own_domains(admin_client, monkeypatch, domain):
    attempted = []

    def refuse(address, timeout=None):
        attempted.append((address, timeout))
        raise OSError("unreachable in the test")

    monkeypatch.setattr(admin.socket, "create_connection", refuse)
    response = admin_client.get("/api/admin/ssl-status", params={"domain": domain} if domain else {})
    assert response.status_code == 200
    assert response.json()["status"] == "NO_SSL_OR_UNREACHABLE"
    assert attempted and attempted[0][0][0] in ("haloticket.duckdns.org", "haloticket-web.pages.dev")
    assert attempted[0][0][1] == 443 and attempted[0][1] <= 5  # fixed port, short timeout


def test_system_logs_come_from_the_configured_file_with_secrets_hidden(admin_client):
    response = admin_client.get("/api/admin/system-logs")
    assert response.status_code == 200
    text = response.text
    assert "payment failed" in text
    assert "a@example.com" not in text and "abc123secret" not in text
    assert "[email]" in text and "[hidden]" in text


def test_system_logs_take_no_path_from_the_client(admin_client):
    response = admin_client.get("/api/admin/system-logs", params={"path": "/etc/passwd", "file": "../../etc/passwd"})
    assert response.status_code == 200
    assert "root:" not in response.text


def test_system_logs_return_only_the_latest_lines(admin_client, monkeypatch, tmp_path):
    big = tmp_path / "big.log"
    big.write_text("\n".join(f"INFO line {i}" for i in range(500)), encoding="utf-8")
    monkeypatch.setattr(settings, "ai_system_log_file", str(big))
    monkeypatch.setattr(settings, "ai_system_log_max_lines", 7)
    lines = admin_client.get("/api/admin/system-logs").json()["entries"]
    assert len(lines) == 7
    assert lines[-1]["details"] == "INFO line 499"


@pytest.mark.parametrize("raw,hidden", [
    ("login ok for someone.name+tag@mail.example.vn", "someone.name+tag@mail.example.vn"),
    ("Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123456789", "abcdefghijklmnopqrstuvwxyz0123456789"),
    ("GET /gift?token=AbCdEf123456&x=1", "AbCdEf123456"),
    ("jwt eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl end", "eyJhbGciOiJSUzI1NiJ9"),
    ("password=hunter2hunter2 failed", "hunter2hunter2"),
    ("hash 4f8a9c1d2b3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a done", "4f8a9c1d2b3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a"),
])
def test_log_lines_are_masked(raw, hidden):
    assert hidden not in admin.mask_sensitive(raw)


def test_ordinary_log_text_is_left_alone():
    line = "2026-10-27 10:00:01 WARN event-service Failed to publish notification for event 70896a7c"
    assert admin.mask_sensitive(line) == line
