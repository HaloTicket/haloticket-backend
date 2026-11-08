"""System monitoring: CPU, memory and uptime from Actuator metrics, and the per-service log folder. No network."""
import asyncio

import httpx
import pytest

from app.core.config import settings
from app.services.ai import service_metrics
from tests.admin_fakes import make_client
from tests.test_admin_ai_routes import FakeResponse, eureka_payload, install_http, stub_infra

METRICS = {
    "process.cpu.usage": 0.1234,
    "process.uptime": 3725.0,
}


def metrics_response(url, params=None):
    if "/actuator/metrics/" not in url:
        return None
    metric = url.rsplit("/actuator/metrics/", 1)[1]
    if metric == "jvm.memory.used":
        value = 150 * 1024 * 1024 if (params or {}).get("tag") == "area:heap" else 50 * 1024 * 1024
    else:
        value = METRICS[metric]
    return {"name": metric, "measurements": [{"statistic": "VALUE", "value": value}]}


def run(coro):
    return asyncio.run(coro)


def client_with(handler):
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def test_metrics_are_formatted_from_the_actuator_answers():
    def handler(request):
        body = metrics_response(str(request.url).split("?")[0], dict(request.url.params))
        return httpx.Response(200, json=body)

    async def go():
        async with client_with(handler) as client:
            return await service_metrics.fetch(client, "10.0.0.5", 9091)

    assert run(go()) == {"cpuLoad": "12.3%", "memoryUsage": "200 MB", "uptime": "1h 2m"}


def test_every_value_that_cannot_be_read_is_none_never_zero():
    def handler(request):
        if "process.cpu.usage" in str(request.url):
            return httpx.Response(404)
        if "process.uptime" in str(request.url):
            raise httpx.ConnectError("refused")
        return httpx.Response(200, json={"measurements": []})

    async def go():
        async with client_with(handler) as client:
            return await service_metrics.fetch(client, "10.0.0.5"), await service_metrics.fetch(client, "")

    answered, no_host = run(go())
    assert answered == {"cpuLoad": None, "memoryUsage": None, "uptime": None}
    assert no_host == {"cpuLoad": None, "memoryUsage": None, "uptime": None}


def test_a_negative_cpu_value_from_the_jvm_is_not_a_percentage():
    def handler(request):
        return httpx.Response(200, json={"measurements": [{"statistic": "VALUE", "value": -1.0}]})

    async def go():
        async with client_with(handler) as client:
            return await service_metrics.fetch(client, "h")

    assert run(go())["cpuLoad"] is None


def test_uptime_format():
    assert service_metrics.format_uptime(59) == "0h 0m"
    assert service_metrics.format_uptime(3725) == "1h 2m"
    assert service_metrics.format_uptime(90000) == "1d 1h 0m"


def test_system_health_fills_cpu_memory_and_uptime_for_the_services_that_answer(monkeypatch):
    import app.api.endpoints.admin as admin_module

    def get(url, params=None):
        body = metrics_response(url, params)
        if body is None:
            return FakeResponse(200, eureka_payload())
        return FakeResponse(404) if "booking" in url else FakeResponse(200, body)

    class Client:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            return False

        async def get(self, url, **kw):
            return get(url, kw.get("params"))

        async def head(self, url, **kw):
            return FakeResponse(200)

    monkeypatch.setattr(admin_module.httpx, "AsyncClient", lambda *a, **k: Client())
    stub_infra(monkeypatch)
    by_name = {e["name"]: e for e in make_client().get("/api/admin/system-health").json()}
    user = by_name["user-service"]
    assert (user["cpuLoad"], user["memoryUsage"], user["uptime"]) == ("12.3%", "200 MB", "1h 2m")
    assert by_name["booking-service"]["cpuLoad"] is None and by_name["booking-service"]["memoryUsage"] is None  # no answer: no data
    assert by_name["eureka"]["uptime"] == "1h 2m"


# log folder
LINE = "2026-11-08T18:25:14.275+07:00 {level} 1 --- [{svc}] [nio-8084-exec-7] c.h.Some : {msg}"


def write(directory, name, lines):
    (directory / name).write_text("\n".join(lines) + "\n", encoding="utf-8")


def test_logs_come_from_the_per_service_files(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "ai_system_log_dir", str(tmp_path))
    write(tmp_path, "booking-service.log", [
        LINE.format(level="ERROR", svc="booking-service", msg="ticket-service source unavailable: Unauthorized token=abc123secret"),
        "java.lang.IllegalStateException: boom",
        "\tat com.haloticket.Foo.bar(Foo.java:10)",
        LINE.format(level="DEBUG", svc="booking-service", msg="noise"),
    ])
    write(tmp_path, "user-service.log", [
        "2026-11-08T18:26:00.000+07:00 INFO 1 --- [user-service] [main] c.h.U : user a@example.com failed login handled normally",
        "2026-11-08T18:27:00.000+07:00  WARN 1 --- [user-service] [main] c.h.U : slow query",
    ])
    (tmp_path / "notes.txt").write_text("not a log", encoding="utf-8")
    body = make_client().get("/api/admin/system-logs").json()
    assert body["source"] == {"status": "ok"}
    entries = body["entries"]
    assert [e["service"] for e in entries] == ["user-service", "user-service", "booking-service"]  # newest first, from the file names
    by_status = {(e["service"], e["status"]) for e in entries}
    assert ("booking-service", "ERROR") in by_status and ("user-service", "WARN") in by_status and ("user-service", "INFO") in by_status
    flat = " ".join(e["details"] for e in entries)
    assert "abc123secret" not in flat and "a@example.com" not in flat  # masked
    assert "IllegalStateException" not in flat and "Foo.java" not in flat and "noise" not in flat  # stack and DEBUG lines left out
    info = next(e for e in entries if e["status"] == "INFO")
    assert info["status"] == "INFO"  # the word "failed" inside an INFO message does not make it an error


def test_empty_and_missing_log_folders(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "ai_system_log_dir", str(tmp_path))
    assert make_client().get("/api/admin/system-logs").json() == {"entries": [], "source": {"status": "empty"}}
    monkeypatch.setattr(settings, "ai_system_log_dir", str(tmp_path / "missing"))
    assert make_client().get("/api/admin/system-logs").json() == {"entries": [], "source": {"status": "unavailable"}}


def test_the_legacy_single_file_still_works_when_no_folder_is_set(tmp_path, monkeypatch):
    log_file = tmp_path / "event-error.log"
    log_file.write_text("ERROR payment failed\n", encoding="utf-8")
    monkeypatch.setattr(settings, "ai_system_log_dir", "")
    monkeypatch.setattr(settings, "ai_system_log_file", str(log_file))
    body = make_client().get("/api/admin/system-logs").json()
    assert body["source"]["status"] == "ok" and body["entries"][0]["status"] == "ERROR"
