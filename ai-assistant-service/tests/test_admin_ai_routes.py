"""Admin routes on the AI foundation: ssl, health, logs, ai/health. No network, no model."""
import json
import time
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.endpoints import admin
from app.core.config import settings
from app.core.errors import ai_error, install_error_handlers, AI_UNAVAILABLE
from app.core.security import TokenVerifier, get_verifier
from app.services.ai import system_probe

ISSUER = "https://login.example.test/realms/haloticket"
KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def _token(roles=("ROLE_ADMIN",)):
    pem = KEY.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return jwt.encode({"iss": ISSUER, "sub": "admin-1", "roles": list(roles), "exp": int(time.time()) + 300}, pem, algorithm="RS256")


@pytest.fixture
def api():
    app = FastAPI()
    install_error_handlers(app)
    app.include_router(admin.router, prefix="/api/admin")
    app.dependency_overrides[get_verifier] = lambda: TokenVerifier(ISSUER, lambda _t: KEY.public_key())
    client = TestClient(app)
    client.headers.update({"Authorization": "Bearer " + _token()})
    return client


def fake_model(monkeypatch, answer, seen=None):
    def invoke(profile, messages, actor=None, tools=None, tier=None):
        if seen is not None:
            seen.append((profile, messages, actor))
        if isinstance(answer, Exception):
            raise answer
        return SimpleNamespace(content=answer)

    monkeypatch.setattr(admin, "ai_invoke", invoke)


RISK_BODY = {"event_id": "e1", "title": "Concert", "description": "A night of music", "organizer_name": "Org"}


# risk scoring and the assistant: see test_risk_*.py, test_admin_tools.py (they moved to admin_intel.py)

# ssl status
class FakeTlsSocket:
    def __init__(self, cert, version="TLSv1.3", port=443):
        self._cert, self._version, self._port = cert, version, port

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def getpeercert(self):
        return self._cert

    def version(self):
        return self._version

    def getpeername(self):
        return ("1.2.3.4", self._port)


def install_tls(monkeypatch, days, version="TLSv1.2", port=443):
    expire = datetime.now(timezone.utc) + timedelta(days=days, hours=1)
    cert = {"notAfter": expire.strftime("%b %d %H:%M:%S %Y GMT"), "issuer": ((("commonName", "Test CA"),),)}
    monkeypatch.setattr(admin.socket, "create_connection", lambda address, timeout=None: FakeTlsSocket(None))
    context = SimpleNamespace(wrap_socket=lambda sock, server_hostname=None: FakeTlsSocket(cert, version, port))
    monkeypatch.setattr(admin.ssl, "create_default_context", lambda: context)


@pytest.fixture
def ssl_api(api, monkeypatch):
    monkeypatch.setattr(settings, "alert_ssl_domains", "haloticket.duckdns.org")
    return api


def test_ssl_protocol_and_port_come_from_the_connection(ssl_api, monkeypatch):
    install_tls(monkeypatch, 60, version="TLSv1.2", port=8443)
    body = ssl_api.get("/api/admin/ssl-status").json()
    assert body["protocol"] == "TLSv1.2" and body["port"] == 8443
    assert body["status"] == "ACTIVE_VALID" and body["has_ssl"] is True and body["issuer"] == "Test CA"
    assert 58 <= body["remaining_days"] <= 60
    assert set(body) == {"has_ssl", "domain", "protocol", "port", "status", "issuer", "remaining_days", "expire_date", "message"}
    assert "1.3" not in body["protocol"] and "HTTPS" not in body["protocol"]


@pytest.mark.parametrize("days,status", [(-3, "EXPIRED"), (0, "EXPIRING_SOON"), (15, "EXPIRING_SOON"), (16, "ACTIVE_VALID")])
def test_ssl_status_thresholds(ssl_api, monkeypatch, days, status):
    install_tls(monkeypatch, days)
    body = ssl_api.get("/api/admin/ssl-status").json()
    assert body["status"] == status
    if status == "EXPIRED":
        assert body["remaining_days"] < 0


def test_ssl_failure_message_is_generic(ssl_api, monkeypatch):
    def boom(address, timeout=None):
        raise OSError("secret internal detail 10.9.8.7")

    monkeypatch.setattr(admin.socket, "create_connection", boom)
    response = ssl_api.get("/api/admin/ssl-status")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "NO_SSL_OR_UNREACHABLE" and "secret" not in response.text and "10.9.8.7" not in response.text


# system health
class FakeResponse:
    def __init__(self, status_code=200, payload=None):
        self.status_code, self._payload = status_code, payload

    def json(self):
        return self._payload


def eureka_payload():
    def inst(name, port, status, home):
        return {"name": name, "instance": [{"status": status, "port": {"$": port, "@enabled": "true"}, "homePageUrl": home}]}

    return {"applications": {"application": [
        inst("USER-SERVICE", 8081, "UP", "http://user:8081/"),
        inst("EVENT-SERVICE", 8082, "UP", "http://event:8082/"),
        inst("BOOKING-SERVICE", 8083, "DOWN", "http://booking:8083/"),
        inst("AI-ASSISTANT-SERVICE", 8089, "UP", "http://ai:8089/"),
    ]}}


def install_http(monkeypatch, get, head):
    class FakeClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def get(self, url, **kw):
            return get(url)

        async def head(self, url, **kw):
            return head(url)

    monkeypatch.setattr(admin.httpx, "AsyncClient", lambda *a, **k: FakeClient())


def stub_infra(monkeypatch, pg="UP", redis="UP"):
    monkeypatch.setattr(system_probe, "check_postgres", lambda: {"name": "postgres", "port": 5432, "type": "PostgreSQL", "status": pg,
                                                                 "uptime": None, "latencyMs": 3, "memoryUsage": None, "cpuLoad": None})
    monkeypatch.setattr(system_probe, "check_redis", lambda: {"name": "redis", "port": 6379, "type": "Redis", "status": redis,
                                                              "uptime": None, "latencyMs": 1, "memoryUsage": None, "cpuLoad": None})


def test_system_health_reports_only_real_values(api, monkeypatch):
    def head(url):
        if "booking" in url:
            return FakeResponse(503)
        if "event" in url:
            raise httpx.ConnectError("down")
        return FakeResponse(200)

    install_http(monkeypatch, lambda url: FakeResponse(200, eureka_payload()), head)
    stub_infra(monkeypatch)
    entries = api.get("/api/admin/system-health").json()
    by_name = {e["name"]: e for e in entries}
    assert set(by_name) == {"user-service", "event-service", "booking-service", "ai-assistant-service", "postgres", "redis", "eureka"}
    assert "kafka" not in by_name and "infrastructure" not in by_name and "discovery-server" not in by_name
    for e in entries:
        assert set(e) == {"name", "port", "type", "status", "uptime", "latencyMs", "memoryUsage", "cpuLoad"}
        assert e["status"] in ("UP", "DOWN", "UNKNOWN") and e["uptime"] != "24h 12m" and (e["cpuLoad"] is None or e["name"] == "ai-assistant-service")  # only this process can measure its own CPU here
    assert by_name["user-service"]["port"] == 8081 and by_name["user-service"]["status"] == "UP"
    assert isinstance(by_name["user-service"]["latencyMs"], int)
    assert by_name["event-service"]["latencyMs"] is None  # HEAD failed
    assert by_name["booking-service"]["status"] == "DOWN" and by_name["booking-service"]["latencyMs"] is None  # HEAD 503
    for name in ("user-service", "event-service", "booking-service"):
        assert by_name[name]["uptime"] is None and by_name[name]["memoryUsage"] is None
    assert by_name["ai-assistant-service"]["uptime"] is not None and by_name["ai-assistant-service"]["port"] == 8089
    assert by_name["eureka"]["status"] == "UP"


def test_system_health_when_eureka_is_down(api, monkeypatch):
    def get(url):
        raise httpx.ConnectError("refused")

    install_http(monkeypatch, get, lambda url: FakeResponse(200))
    stub_infra(monkeypatch, pg="DOWN", redis="UNKNOWN")
    entries = api.get("/api/admin/system-health").json()
    names = [e["name"] for e in entries]
    assert names == ["ai-assistant-service", "postgres", "redis", "eureka"]
    assert entries[-1]["status"] == "DOWN" and entries[1]["status"] == "DOWN" and entries[2]["status"] == "UNKNOWN"


def test_system_health_eureka_error_status_counts_as_down(api, monkeypatch):
    install_http(monkeypatch, lambda url: FakeResponse(500, {}), lambda url: FakeResponse(200))
    stub_infra(monkeypatch)
    entries = api.get("/api/admin/system-health").json()
    assert [e["name"] for e in entries if e["name"] == "eureka"] == ["eureka"]
    assert next(e for e in entries if e["name"] == "eureka")["status"] == "DOWN"


def test_postgres_check_with_fake_engine(monkeypatch):
    executed = []

    class Conn:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def execute(self, statement):
            executed.append(str(statement))

        def rollback(self):
            pass

    engine = SimpleNamespace(connect=lambda: Conn(), url=SimpleNamespace(port=5433))
    monkeypatch.setattr(system_probe.session, "engine", engine)
    result = system_probe.check_postgres()
    assert result["status"] == "UP" and result["port"] == 5433 and isinstance(result["latencyMs"], int)
    assert any("SELECT 1" in s for s in executed)

    def broken():
        raise RuntimeError("password authentication failed for user x")

    monkeypatch.setattr(system_probe.session, "engine", SimpleNamespace(connect=broken, url=SimpleNamespace(port=5433)))
    down = system_probe.check_postgres()
    assert down["status"] == "DOWN" and down["latencyMs"] is None and "password" not in json.dumps(down)

    monkeypatch.setattr(system_probe.session, "engine", None)
    assert system_probe.check_postgres()["status"] == "UNKNOWN"


def test_redis_check_with_fake_client(monkeypatch):
    monkeypatch.setattr(settings, "redis_url", "")
    assert system_probe.check_redis()["status"] == "UNKNOWN"
    monkeypatch.setattr(settings, "redis_url", "redis://:pw@redis:6380/1")
    monkeypatch.setattr(system_probe, "make_redis", lambda url: SimpleNamespace(ping=lambda: True))
    up = system_probe.check_redis()
    assert up["status"] == "UP" and up["port"] == 6380 and "pw" not in json.dumps(up)

    def refuse():
        raise ConnectionError("refused")

    monkeypatch.setattr(system_probe, "make_redis", lambda url: SimpleNamespace(ping=refuse))
    assert system_probe.check_redis()["status"] == "DOWN"


def test_process_memory_from_proc_status(tmp_path):
    status = tmp_path / "status"
    status.write_text("Name:\tpython\nVmRSS:\t   20480 kB\n", encoding="utf-8")
    assert system_probe.process_memory(str(status)) == "20 MB"
    assert system_probe.process_memory(str(tmp_path / "missing")) is None
    assert system_probe.process_uptime().endswith("m")


# system logs
def test_system_logs_ok_shape(api, monkeypatch, tmp_path):
    log_file = tmp_path / "app.log"
    log_file.write_text("2026-10-27 10:00:01 ERROR payment failed a@example.com\nplain line without time\n", encoding="utf-8")
    monkeypatch.setattr(settings, "ai_system_log_file", str(log_file))
    body = api.get("/api/admin/system-logs").json()
    assert body["source"] == {"status": "ok"} and len(body["entries"]) == 2
    first, second = body["entries"]
    assert first["time"] == "2026-10-27T10:00:01" and second["time"] is None
    assert first["status"] == "ERROR" and second["status"] == "INFO" and first["service"] == "payment-service"
    assert first["operator"] is None and first["ipAddress"] is None and first["endpoint"] is None
    assert len(first["id"]) == 12 and first["id"] != second["id"] and "a@example.com" not in json.dumps(body)
    again = api.get("/api/admin/system-logs").json()
    assert [e["id"] for e in again["entries"]] == [e["id"] for e in body["entries"]]  # stable
    assert all(len(e["event"]) <= 180 for e in body["entries"])


def test_system_logs_empty_and_unavailable(api, monkeypatch, tmp_path):
    empty = tmp_path / "empty.log"
    empty.write_text("\n  \n", encoding="utf-8")
    monkeypatch.setattr(settings, "ai_system_log_file", str(empty))
    assert api.get("/api/admin/system-logs").json() == {"entries": [], "source": {"status": "empty"}}
    monkeypatch.setattr(settings, "ai_system_log_file", str(tmp_path / "missing.log"))
    body = api.get("/api/admin/system-logs").json()
    assert body == {"entries": [], "source": {"status": "unavailable"}}
    assert "HEALTHY" not in json.dumps(body)
    monkeypatch.setattr(settings, "ai_system_log_file", "")
    assert api.get("/api/admin/system-logs").json()["source"]["status"] == "unavailable"


# ---------------------------------------------------------------- ai/health
def test_ai_health_passive_makes_no_call(api, monkeypatch):
    monkeypatch.setattr(admin, "ai_invoke", lambda *a, **k: pytest.fail("passive health must not call a model"))
    monkeypatch.setattr(admin, "provider_status", lambda: [{"name": "groq", "configured": True, "status": "unknown"}])
    body = api.get("/api/admin/ai/health").json()
    assert body == {"providers": [{"name": "groq", "configured": True, "status": "unknown"}], "probe": None}


def test_ai_health_requires_admin(api):
    api.headers.update({"Authorization": "Bearer " + _token(["ROLE_USER"])})
    assert api.get("/api/admin/ai/health").status_code == 403


def test_ai_health_probe_is_cached_for_ten_minutes(api, monkeypatch):
    calls = []

    def invoke(profile, messages, actor=None, **kw):
        calls.append((profile, messages))
        return SimpleNamespace(content="x")

    monkeypatch.setattr(admin, "ai_invoke", invoke)
    first = api.get("/api/admin/ai/health", params={"probe": 1}).json()["probe"]
    assert first["status"] == "ok" and "checked_at" in first
    second = api.get("/api/admin/ai/health", params={"probe": 1}).json()["probe"]
    assert second == first and len(calls) == 1 and calls[0][0] == "ai_probe"
    admin._probe_cache["until"] = time.time() - 1  # expired
    api.get("/api/admin/ai/health", params={"probe": 1})
    assert len(calls) == 2


def test_ai_health_probe_failure_does_not_raise(api, monkeypatch):
    monkeypatch.setattr(admin, "ai_invoke", lambda *a, **k: (_ for _ in ()).throw(ai_error(AI_UNAVAILABLE)))
    response = api.get("/api/admin/ai/health", params={"probe": 1})
    assert response.status_code == 200
    probe = response.json()["probe"]
    assert probe["status"] == "failed" and probe["error_code"] == "AI_UNAVAILABLE" and "checked_at" in probe


def test_ai_health_probe_is_rate_limited_but_cached_answers_are_free(api, monkeypatch):
    calls = []
    monkeypatch.setattr(admin, "ai_invoke", lambda *a, **k: calls.append(1) or SimpleNamespace(content="x"))
    monkeypatch.setattr(settings, "ai_rl_admin_assistant_per_hour", 1)
    assert api.get("/api/admin/ai/health", params={"probe": 1}).status_code == 200
    assert api.get("/api/admin/ai/health", params={"probe": 1}).status_code == 200  # cache hit, not counted
    admin.reset_probe_cache()
    assert api.get("/api/admin/ai/health", params={"probe": 1}).status_code == 429
    assert len(calls) == 1
