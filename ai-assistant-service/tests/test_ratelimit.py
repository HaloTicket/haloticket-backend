from types import SimpleNamespace

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.core import ratelimit
from app.core.config import settings
from app.core.errors import AiError, install_error_handlers
from app.core.ratelimit import actor_of, client_ip, consume_budget, rate_limit, reset_for_tests


@pytest.fixture(autouse=True)
def _clean(monkeypatch):
    reset_for_tests()
    monkeypatch.setattr(settings, "redis_url", "")
    yield
    reset_for_tests()


def req(host="10.1.2.3", xff=None, claims=None):
    headers = {"x-forwarded-for": xff} if xff else {}
    return SimpleNamespace(client=SimpleNamespace(host=host), headers=headers, state=SimpleNamespace(claims=claims))


def test_client_ip_trusts_xff_only_from_trusted_peer_leftmost():
    assert client_ip(req("10.1.2.3", "8.8.8.8, 10.0.0.1")) == "8.8.8.8"
    assert client_ip(req("203.0.113.9", "8.8.8.8")) == "203.0.113.9"  # spoofed, untrusted peer
    assert client_ip(req("10.1.2.3", "not-an-ip")) == "10.1.2.3"


def test_actor_is_hashed_and_prefers_sub():
    a = actor_of(req(), {"sub": "user-1"})
    b = actor_of(req(), None)
    assert a.startswith("sub:") and len(a) == 16 and "user-1" not in a
    assert b.startswith("ip:") and "10.1.2.3" not in b
    assert actor_of(req(), {"sub": "user-1"}) == a


def test_per_minute_and_per_hour_windows(monkeypatch):
    monkeypatch.setattr(settings, "ai_rl_customer_chat_ip_per_minute", 2)
    monkeypatch.setattr(settings, "ai_rl_customer_chat_ip_per_hour", 3)
    t = 3600.0 * 500  # aligned to an hour start
    ratelimit.check("customer_chat", "ip:a", False, now=t)
    ratelimit.check("customer_chat", "ip:a", False, now=t + 1)
    with pytest.raises(AiError) as e:
        ratelimit.check("customer_chat", "ip:a", False, now=t + 2)
    assert e.value.status == 429 and 1 <= e.value.retry_after <= 61
    # 3rd call of the hour in a new minute passes; the 4th is blocked by the hour window
    ratelimit.check("customer_chat", "ip:a", False, now=t + 70)
    with pytest.raises(AiError) as e2:
        ratelimit.check("customer_chat", "ip:a", False, now=t + 130)
    assert e2.value.retry_after > 3000


def test_ip_and_account_use_different_limits_and_keys(monkeypatch):
    monkeypatch.setattr(settings, "ai_rl_customer_chat_ip_per_minute", 1)
    monkeypatch.setattr(settings, "ai_rl_customer_chat_account_per_minute", 3)
    t = 2_000_000.0
    ratelimit.check("customer_chat", "ip:x", False, now=t)
    with pytest.raises(AiError):
        ratelimit.check("customer_chat", "ip:x", False, now=t)
    for _ in range(3):
        ratelimit.check("customer_chat", "sub:x", True, now=t)
    with pytest.raises(AiError):
        ratelimit.check("customer_chat", "sub:x", True, now=t)


def test_dependency_end_to_end_with_429_header(monkeypatch):
    monkeypatch.setattr(settings, "ai_rl_customer_search_per_minute", 1)
    app = FastAPI()
    install_error_handlers(app)

    @app.post("/s", dependencies=[Depends(rate_limit("customer_search"))])
    def s(body: dict):
        return {"ok": True}

    c = TestClient(app)
    assert c.post("/s", json={}).status_code == 200
    r = c.post("/s", json={})
    assert r.status_code == 429 and r.json()["code"] == "AI_RATE_LIMITED" and "retry-after" in r.headers
    # an invalid body is rejected by the limit before validation
    assert c.post("/s", content="x").status_code == 429


def test_unknown_bucket_fails_fast():
    with pytest.raises(ValueError):
        rate_limit("nope")


class FakePipe:
    def __init__(self, store, fail):
        self.store, self.fail, self.ops = store, fail, []

    def incr(self, k):
        self.ops.append(("incr", k))

    def expire(self, k, ttl):
        self.ops.append(("expire", k, ttl))

    def execute(self):
        if self.fail:
            raise ConnectionError("down")
        out = []
        for op in self.ops:
            if op[0] == "incr":
                self.store[op[1]] = self.store.get(op[1], 0) + 1
                out.append(self.store[op[1]])
            else:
                out.append(True)
        return out


class FakeRedis:
    def __init__(self, fail=False):
        self.store, self.fail, self.calls = {}, fail, 0

    def pipeline(self):
        self.calls += 1
        return FakePipe(self.store, self.fail)


def test_redis_used_with_expected_key_and_fallback_on_failure(monkeypatch):
    monkeypatch.setattr(settings, "redis_url", "redis://fake/1")
    monkeypatch.setattr(settings, "ai_rl_customer_search_per_minute", 5)
    fake = FakeRedis()
    monkeypatch.setattr(ratelimit, "_redis_factory", lambda url: fake)
    ratelimit.check("customer_search", "ip:k", False, now=120.0)
    assert list(fake.store) == ["aiq:customer_search:m2:ip:k"]

    broken = FakeRedis(fail=True)
    ratelimit.reset_for_tests()
    monkeypatch.setattr(ratelimit, "_redis_factory", lambda url: broken)
    monkeypatch.setattr(settings, "ai_rl_customer_search_per_minute", 1)
    ratelimit.check("customer_search", "ip:k", False, now=120.0)  # falls back, does not raise
    with pytest.raises(AiError):
        ratelimit.check("customer_search", "ip:k", False, now=121.0)  # in-process counting works
    assert broken.calls == 1  # breaker: Redis not retried within 60 s


def test_missing_redis_package_falls_back(monkeypatch):
    import builtins
    real = builtins.__import__

    def fake_import(name, *a, **k):
        if name == "redis":
            raise ImportError
        return real(name, *a, **k)

    monkeypatch.setattr(settings, "redis_url", "redis://x/1")
    monkeypatch.setattr(builtins, "__import__", fake_import)
    ratelimit.check("customer_search", "ip:z", False, now=10.0)


def test_consume_budget(monkeypatch):
    monkeypatch.setattr(settings, "ai_daily_call_budget", 2)
    consume_budget()
    consume_budget()
    with pytest.raises(AiError) as e:
        consume_budget()
    assert e.value.code == "AI_BUDGET_EXHAUSTED" and e.value.status == 429


def test_anonymous_callers_cannot_use_more_than_their_share_of_the_daily_budget(monkeypatch):
    monkeypatch.setattr(settings, "ai_daily_call_budget", 10)
    monkeypatch.setattr(settings, "ai_anonymous_budget_share", 0.5)
    for _ in range(5):
        consume_budget(actor="ip:abc")
    with pytest.raises(AiError) as e:
        consume_budget(actor="ip:def")  # một IP khác cũng dùng chung phần ẩn danh
    assert e.value.code == "AI_BUDGET_EXHAUSTED" and e.value.status == 429


def test_signed_in_users_still_have_budget_after_anonymous_traffic_is_cut_off(monkeypatch):
    monkeypatch.setattr(settings, "ai_daily_call_budget", 10)
    monkeypatch.setattr(settings, "ai_anonymous_budget_share", 0.5)
    for _ in range(5):
        consume_budget(actor="ip:abc")
    for _ in range(3):  # lượt ẩn danh bị từ chối không được trừ vào ngân sách chung
        with pytest.raises(AiError):
            consume_budget(actor="ip:abc")
    for _ in range(5):
        consume_budget(actor="sub:user1")
    with pytest.raises(AiError):
        consume_budget(actor="sub:user1")  # hết ngân sách chung


def test_a_call_without_an_actor_is_counted_against_the_total_only(monkeypatch):
    monkeypatch.setattr(settings, "ai_daily_call_budget", 3)
    monkeypatch.setattr(settings, "ai_anonymous_budget_share", 0.0)
    consume_budget()
    consume_budget(actor="sub:u")
    consume_budget()
    with pytest.raises(AiError):
        consume_budget()


def test_a_zero_share_blocks_every_anonymous_call_but_not_signed_in_ones(monkeypatch):
    monkeypatch.setattr(settings, "ai_daily_call_budget", 5)
    monkeypatch.setattr(settings, "ai_anonymous_budget_share", 0.0)
    with pytest.raises(AiError):
        consume_budget(actor="ip:x")
    consume_budget(actor="sub:u")
