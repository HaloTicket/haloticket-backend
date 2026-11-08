from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

from app.core.errors import AiError, ai_error, install_error_handlers, AI_RATE_LIMITED, AI_UNAVAILABLE, AI_QUESTION_REFUSED, \
    AI_INPUT_INVALID, AI_BUDGET_EXHAUSTED, AI_DB_NOT_CONFIGURED, AI_GENERATION_FAILED


def _client():
    app = FastAPI()
    install_error_handlers(app)

    @app.get("/limited")
    def limited():
        raise ai_error(AI_RATE_LIMITED, retry_after=42)

    @app.get("/down")
    def down():
        raise ai_error(AI_UNAVAILABLE)

    @app.get("/http")
    def http():
        raise HTTPException(status_code=403, detail={"code": "X", "message": "y"})

    @app.get("/boom")
    def boom():
        raise ValueError("secret detail")

    return TestClient(app, raise_server_exceptions=False)


def test_status_mapping():
    expected = {AI_INPUT_INVALID: 422, AI_RATE_LIMITED: 429, AI_BUDGET_EXHAUSTED: 429, AI_UNAVAILABLE: 503,
                AI_DB_NOT_CONFIGURED: 503, AI_GENERATION_FAILED: 502, AI_QUESTION_REFUSED: 422}
    for code, status in expected.items():
        err = ai_error(code)
        assert isinstance(err, AiError) and err.status == status and err.code == code and err.message


def test_flat_body_with_retry_after_header():
    r = _client().get("/limited")
    assert r.status_code == 429
    assert r.json() == {"code": "AI_RATE_LIMITED", "message": ai_error(AI_RATE_LIMITED).message, "retry_after_seconds": 42}
    assert r.headers["retry-after"] == "42"


def test_no_retry_fields_on_503():
    r = _client().get("/down")
    assert r.status_code == 503 and set(r.json()) == {"code", "message"} and "retry-after" not in r.headers


def test_http_exception_unchanged_and_other_errors_not_leaked():
    c = _client()
    assert c.get("/http").json() == {"detail": {"code": "X", "message": "y"}}
    r = c.get("/boom")
    assert r.status_code == 500 and "secret" not in r.text
