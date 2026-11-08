import pytest

from app.core.config import settings
from app.services.ai import usage

ARGS = dict(route="r", tier="fast", provider="groq", model="m", ok=True, error_code=None, latency_ms=5,
            input_tokens=1, output_tokens=2, estimated=False, actor="ip:abc", attempts=1)


class Conn:
    def __init__(self, owner):
        self.owner = owner

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def execute(self, stmt, params):
        if self.owner.fail:
            raise RuntimeError("db down")
        self.owner.rows.append(params)


class Engine:
    def __init__(self, fail=False):
        self.fail, self.rows, self.begins = fail, [], 0

    def begin(self):
        self.begins += 1
        return Conn(self)


@pytest.fixture(autouse=True)
def _clean():
    usage.reset_for_tests()
    yield
    usage.reset_for_tests()


def test_noop_without_url(monkeypatch):
    monkeypatch.setattr(settings, "ai_ops_database_url", "")
    monkeypatch.setattr(usage, "_get_engine", lambda url: pytest.fail("no engine expected"))
    usage.record(**ARGS)


def test_insert_has_expected_columns_only(monkeypatch):
    monkeypatch.setattr(settings, "ai_ops_database_url", "postgresql://x/y")
    eng = Engine()
    monkeypatch.setattr(usage, "_get_engine", lambda url: eng)
    usage.record(**ARGS)
    assert len(eng.rows) == 1
    assert set(eng.rows[0]) == {"route", "tier", "provider", "model", "ok", "error_code", "latency_ms", "input_tokens",
                                "output_tokens", "estimated", "actor", "attempts"}


def test_failure_does_not_raise_and_breaker_skips(monkeypatch):
    monkeypatch.setattr(settings, "ai_ops_database_url", "postgresql://x/y")
    eng = Engine(fail=True)
    monkeypatch.setattr(usage, "_get_engine", lambda url: eng)
    usage.record(**ARGS)
    usage.record(**ARGS)
    usage.record(**ARGS)
    assert eng.begins == 1  # breaker open after first failure
    usage._breaker_until = 0
    eng.fail = False
    usage.record(**ARGS)
    assert len(eng.rows) == 1


def test_engine_build_failure_does_not_raise(monkeypatch):
    monkeypatch.setattr(settings, "ai_ops_database_url", "postgresql://x/y")

    def boom(url):
        raise RuntimeError("bad")

    monkeypatch.setattr(usage, "_get_engine", boom)
    usage.record(**ARGS)
