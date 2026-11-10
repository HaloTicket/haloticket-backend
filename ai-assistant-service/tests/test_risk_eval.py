"""The versioned eval files (tests/eval/*.jsonl, read by A5): schema checks, and the 30 risk cases run through layers A and B
with a stand-in for layer C. No model, no network. A5 runs the needs_model cases against the real model."""
import json
import os
from datetime import datetime, timezone

import pytest

from app.services.risk import content, engine, rules, signals

EVAL = os.path.join(os.path.dirname(__file__), "eval")
NOW = datetime(2026, 11, 8, 12, 0, tzinfo=timezone.utc)
LEVELS = ["LOW", "MEDIUM", "HIGH"]


def load(name):
    with open(os.path.join(EVAL, name), "r", encoding="utf-8") as handle:
        header = json.loads(handle.readline())
        rows = [json.loads(line) for line in handle if line.strip()]
    assert header["version"] == "1" and header["count"] == len(rows)
    return header, rows


RISK = load("risk_cases.v1.jsonl")[1]
QUESTIONS = load("assistant_questions.v1.jsonl")[1]


def source(fixtures):
    def reader(name):
        def read(*_args):
            value = fixtures[name]
            if value == "unavailable":
                raise RuntimeError("unavailable")
            return value
        return read

    return signals.SignalSource(user=reader("user"), approvals=reader("approvals"), category_prices=reader("category_prices"))


def assess(case):
    built = content.build(case["event"])
    ruleset = rules.load_rules()
    found = rules.evaluate(built["texts"], ruleset)
    results = signals.evaluate(case["event"], built["texts"], source(case["fixtures"]), NOW)
    flags = engine.validate_ai_flags(case["fixtures"].get("model_flags", []), built["texts"]) if case["expect"]["needs_model"] else []
    return engine.combine(found, results, "ran" if case["expect"]["needs_model"] else "skipped", flags, ruleset.version)


def test_the_files_have_the_promised_size_and_unique_ids():
    assert len(RISK) == 30 and len(QUESTIONS) == 20
    assert len({c["id"] for c in RISK}) == 30 and len({q["id"] for q in QUESTIONS}) == 20
    categories = {c["category"] for c in RISK}
    assert {"clean", "contact_allowed", "social", "ban_context", "prohibited", "injection", "price", "duplicate", "missing"} <= categories


def test_the_question_file_has_the_fields_a5_needs():
    for question in QUESTIONS:
        assert question["question"].strip() and set(question["expect"]) == {"tools_any_of", "must_refuse_write", "numbers_from_tools"}
    assert sum(1 for q in QUESTIONS if q["expect"]["must_refuse_write"]) == 5
    known = {"list_pending_events", "get_event_detail", "get_kpis", "get_attention_feed", "query_data"}
    assert all(set(q["expect"]["tools_any_of"]) <= known for q in QUESTIONS)


@pytest.mark.parametrize("case", RISK, ids=[c["id"] for c in RISK])
def test_risk_case(case):
    result = assess(case)
    expect = case["expect"]
    codes = {r["code"] for r in result["reason_items"]}
    assert LEVELS.index(expect["min_level"]) <= LEVELS.index(result["risk_level"]) <= LEVELS.index(expect["max_level"]), (result["risk_score"], codes)
    for code in expect["codes_must_include"]:
        assert code in codes, (code, codes)
    for code in expect["codes_must_not_include"]:
        assert code not in codes, (code, codes)
    if expect["complete"] is not None:
        assert result["complete"] is expect["complete"]
    assert result["ruleset_version"] == "3"


def test_injection_cases_score_exactly_like_the_same_text_without_the_instruction():
    case = next(c for c in RISK if c["category"] == "injection")
    plain = json.loads(json.dumps(case))
    plain["event"]["descriptionText"] = plain["event"]["description"] = "Bán thuốc lá điện tử giá rẻ."
    assert assess(case)["risk_score"] == assess(plain)["risk_score"]
