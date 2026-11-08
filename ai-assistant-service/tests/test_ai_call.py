import time

import pytest

from app.core import ratelimit
from app.core.config import settings
from app.core.errors import AiError
from app.services.ai import ai_call, usage

BUILT = []
BEHAVIOR = {}


class FakeMsg:
    def __init__(self, content, usage_metadata=None, tool_calls=None):
        self.content, self.usage_metadata, self.tool_calls = content, usage_metadata, tool_calls or []


def make_cls(provider):
    class Fake:
        def __init__(self, **kw):
            self.kw, self.provider = kw, provider
            BUILT.append((provider, kw))

        def bind_tools(self, tools):
            return self

        def invoke(self, messages):
            return BEHAVIOR[provider]()
    return Fake


@pytest.fixture(autouse=True)
def env(monkeypatch):
    BUILT.clear()
    BEHAVIOR.clear()
    ai_call.reset_for_tests()
    monkeypatch.setattr(ai_call, "ChatGroq", make_cls("groq"))
    monkeypatch.setattr(ai_call, "ChatOpenAI", make_cls("openai"))
    monkeypatch.setattr(ai_call, "ChatGoogleGenerativeAI", make_cls("gemini"))
    monkeypatch.setattr(settings, "groq_api_key", "g1,g2")
    monkeypatch.setattr(settings, "openai_api_key", "o1")
    monkeypatch.setattr(settings, "gemini_api_key", "m1")
    monkeypatch.setattr(settings, "ai_daily_call_budget", 10000)
    monkeypatch.setattr(settings, "redis_url", "")
    calls = []
    monkeypatch.setattr(usage, "record", lambda **kw: calls.append(kw))
    ratelimit.reset_for_tests()
    yield calls
    ai_call.reset_for_tests()
    ratelimit.reset_for_tests()


def bad():
    raise RuntimeError("secret-key-xyz")


def test_first_provider_success_and_max_tokens_passed(env):
    BEHAVIOR["groq"] = lambda: FakeMsg("hi", {"input_tokens": 3, "output_tokens": 4, "total_tokens": 7})
    out = ai_call.ai_invoke("risk_scoring", "hello", actor="ip:a")
    assert out.content == "hi"
    kw = BUILT[0][1]
    assert BUILT[0][0] == "groq" and kw["max_tokens"] == settings.ai_max_tokens_risk_scoring
    assert kw["max_retries"] == 1 and kw["model"] == "openai/gpt-oss-20b" and kw["api_key"] == "g1"
    assert len(env) == 1 and env[0]["ok"] and env[0]["input_tokens"] == 3 and env[0]["estimated"] is False
    assert env[0]["actor"] == "ip:a" and env[0]["attempts"] == 1


def test_fallback_to_second_never_third(env):
    BEHAVIOR["groq"] = bad
    BEHAVIOR["openai"] = bad
    BEHAVIOR["gemini"] = lambda: FakeMsg("never")
    with pytest.raises(AiError) as e:
        ai_call.ai_invoke("analysis", "x")
    assert e.value.code == "AI_UNAVAILABLE" and e.value.status == 503 and "secret" not in e.value.message
    assert [b[0] for b in BUILT] == ["groq", "openai"]
    assert len(env) == 2 and not any(c["ok"] for c in env) and [c["attempts"] for c in env] == [1, 2]


def test_fallback_success_on_second_and_estimated_tokens(env):
    BEHAVIOR["groq"] = bad
    BEHAVIOR["openai"] = lambda: FakeMsg("abcdefgh")
    out = ai_call.ai_invoke("event_content", "abcdefghabcdefgh")
    assert out.content == "abcdefgh"
    assert BUILT[0][1]["model"] == "openai/gpt-oss-120b"  # smart tier
    assert env[1]["ok"] and env[1]["estimated"] is True and env[1]["output_tokens"] == 2


def test_gemini_uses_max_output_tokens(env, monkeypatch):
    monkeypatch.setattr(settings, "groq_api_key", "")
    monkeypatch.setattr(settings, "openai_api_key", "")
    BEHAVIOR["gemini"] = lambda: FakeMsg("ok")
    ai_call.ai_invoke("analysis", "q")
    assert BUILT[0][1]["max_output_tokens"] == settings.ai_max_tokens_analysis and "max_tokens" not in BUILT[0][1]


def test_empty_content_tries_next_then_generation_failed(env):
    BEHAVIOR["groq"] = lambda: FakeMsg("  ")
    BEHAVIOR["openai"] = lambda: FakeMsg("")
    with pytest.raises(AiError) as e:
        ai_call.ai_invoke("risk_scoring", "x")
    assert e.value.code == "AI_GENERATION_FAILED" and e.value.status == 502
    assert len(env) == 2 and env[0]["error_code"] == "EMPTY_RESPONSE"


def test_tool_calls_count_as_non_empty(env):
    BEHAVIOR["groq"] = lambda: FakeMsg("", tool_calls=[{"name": "t", "args": {}, "id": "1"}])
    assert ai_call.ai_invoke("risk_scoring", "x", tools=[object()]).tool_calls


def test_no_keys_is_unavailable(env, monkeypatch):
    monkeypatch.setattr(settings, "groq_api_key", "")
    monkeypatch.setattr(settings, "openai_api_key", "")
    monkeypatch.setattr(settings, "gemini_api_key", "your-default-key-if-missing")
    with pytest.raises(AiError) as e:
        ai_call.ai_invoke("risk_scoring", "x")
    assert e.value.code == "AI_UNAVAILABLE" and not BUILT


def test_deadline_enforced_with_slow_fake(env, monkeypatch):
    monkeypatch.setitem(ai_call.PROFILES, "risk_scoring", ai_call.AiProfile("risk_scoring", "fast", 10, 1))

    def slow():
        time.sleep(1.5)
        return FakeMsg("late")
    BEHAVIOR["groq"] = slow
    BEHAVIOR["openai"] = slow
    t = time.monotonic()
    with pytest.raises(AiError) as e:
        ai_call.ai_invoke("risk_scoring", "x")
    assert time.monotonic() - t < 1.4 and e.value.code == "AI_UNAVAILABLE"
    assert env[0]["error_code"] == "TIMEOUT"


def test_real_usage_record_failure_never_propagates(env, monkeypatch):
    monkeypatch.undo()  # drop the recording stub and every patch, then rebuild what this test needs
    usage.reset_for_tests()
    monkeypatch.setattr(ai_call, "ChatGroq", make_cls("groq"))
    monkeypatch.setattr(settings, "groq_api_key", "g1")
    monkeypatch.setattr(settings, "openai_api_key", "")
    monkeypatch.setattr(settings, "gemini_api_key", "")
    monkeypatch.setattr(settings, "ai_ops_database_url", "postgresql://x/y")

    def boom(url):
        raise RuntimeError("db down")
    monkeypatch.setattr(usage, "_get_engine", boom)
    BEHAVIOR["groq"] = lambda: FakeMsg("ok")
    assert ai_call.ai_invoke("risk_scoring", "x").content == "ok"
    usage.reset_for_tests()


def test_budget_consumed_for_customer_chat_only(env, monkeypatch):
    monkeypatch.setattr(settings, "ai_daily_call_budget", 1)
    BEHAVIOR["groq"] = lambda: FakeMsg("ok")
    ai_call.ai_invoke("customer_chat", "x")
    with pytest.raises(AiError) as e:
        ai_call.ai_invoke("customer_chat", "x")
    assert e.value.code == "AI_BUDGET_EXHAUSTED"
    ai_call.ai_invoke("risk_scoring", "x")  # not budgeted


def test_metered_llm_and_provider_status(env):
    BEHAVIOR["groq"] = lambda: FakeMsg("ok")
    assert ai_call.metered_llm("analysis", actor="a").invoke("q", config=None).content == "ok"
    status = {s["name"]: s for s in ai_call.provider_status()}
    assert set(status) == {"groq", "openai", "gemini"}
    assert status["groq"]["status"] == "ok" and status["groq"]["ok_count"] == 1 and status["groq"]["configured"]
    assert status["openai"]["status"] == "unknown"
    assert "g1" not in str(status)


def test_profiles():
    p = ai_call.PROFILES
    assert p["event_content"].tier == "smart" and p["ai_probe"].max_tokens == 1
    for name in ("customer_chat", "admin_assistant", "risk_scoring", "analysis", "voucher_strategy", "ai_probe"):
        assert p[name].tier == "fast"
