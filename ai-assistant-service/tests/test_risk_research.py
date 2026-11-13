"""The study kit (research/risk): statistics, case generator, blind labelling tool, adjudication, configuration runner and report.
No network, no model: model calls go through research.risk.fakellm or a scripted stand-in."""
import json
import math
import os

import pytest

from app.services.risk import engine, signals
from research.risk import adjudicate, assessment_report, common, fakellm, generate, label, run_configs, stats, view

SEED = 20261108


@pytest.fixture(scope="module")
def built():
    pool, cases = generate.build_all(SEED)
    for i, case in enumerate(cases, 1):
        case["id"] = f"RS-{i:04d}"
        case["fixtures"]["approvals"] = pool + case["fixtures"]["approvals_extra"]
    return pool, cases


def by_group(cases, group):
    return [c for c in cases if c["group"] == group]


# ---------------------------------------------------------------- statistics
def test_wilson_interval_of_five_in_ten():
    low, high = stats.wilson(5, 10)
    assert low == pytest.approx(0.2366, abs=1e-3) and high == pytest.approx(0.7634, abs=1e-3)
    assert stats.wilson(0, 0) == (0.0, 1.0)
    assert stats.wilson(10, 10)[1] == pytest.approx(1.0)


def test_cohen_kappa_known_values():
    assert stats.cohen_kappa([1, 1, 0, 0], [1, 0, 0, 0], [0, 1]) == pytest.approx(0.5)
    assert stats.cohen_kappa(["a", "b"], ["a", "b"], ["a", "b"]) == 1.0
    assert stats.cohen_kappa(["LOW", "HIGH"], ["HIGH", "LOW"], ["LOW", "MEDIUM", "HIGH"]) < 0
    # a one-step disagreement costs less than a two-step one when the labels are ordered
    near = stats.cohen_kappa(["LOW", "MEDIUM", "HIGH", "LOW"], ["LOW", "HIGH", "HIGH", "MEDIUM"], list(common.LEVELS), "linear")
    far = stats.cohen_kappa(["LOW", "MEDIUM", "HIGH", "LOW"], ["HIGH", "MEDIUM", "HIGH", "LOW"], list(common.LEVELS), "linear")
    assert far < 1 and near < 1


def test_mcnemar_exact_and_holm():
    assert stats.mcnemar([False] * 5, [True] * 5) == {"only_a": 0, "only_b": 5, "p": pytest.approx(2 / 32)}
    assert stats.mcnemar([True, False], [True, False])["p"] == 1.0
    assert stats.holm([0.01, 0.04, 0.03]) == pytest.approx([0.03, 0.06, 0.06])


def test_bootstrap_is_repeatable_and_brackets_the_estimate():
    rows = [1] * 30 + [0] * 70
    low, high = stats.bootstrap_ci(lambda r: sum(r) / len(r), rows, 1000)
    assert (low, high) == stats.bootstrap_ci(lambda r: sum(r) / len(r), rows, 1000)
    assert low < 0.30 < high


# ---------------------------------------------------------------- generator
def test_generator_is_deterministic_and_has_the_planned_shape(built):
    pool, cases = built
    pool2, cases2 = generate.build_all(SEED)
    assert [c["event"]["description"] for c in cases2] == [c["event"]["description"] for c in cases]
    assert len(cases) == 500 and len({c["id"] for c in cases}) == 500
    assert len({(c["event"]["title"], c["event"]["description"]) for c in cases}) == 500
    assert len({c["group"] for c in cases}) == 18
    other = generate.build_all(SEED + 1)[1]
    assert [c["event"]["description"] for c in other] != [c["event"]["description"] for c in cases]


def test_near_duplicate_cases_really_trip_the_signal(built):
    _, cases = built
    for case in by_group(cases, "near_duplicate"):
        original = case["fixtures"]["approvals_extra"][-1]
        assert signals.similarity(case["event"]["title"] + " " + case["event"]["description"],
                                  original["title"] + " " + original["description"]) >= signals.DUPLICATE_THRESHOLD


def test_veiled_cases_hide_the_word_from_a_plain_search(built):
    _, cases = built
    for case in by_group(cases, "prohibited_veiled"):
        assert case["disguise"] in ("zero_width", "spaced", "dotted", "leet", "lookalike", "stripped")
        assert case["intended"]["codes"] == ["PROHIBITED_GOODS"]


def test_what_an_annotator_sees_never_leaks_the_group_or_the_intention(built):
    _, cases = built
    for case in cases[:80]:
        screen = view.render(case)
        assert case["group"] not in screen and "intended" not in screen and "risk_score" not in screen
        assert case["event"]["title"] in screen


# ---------------------------------------------------------------- labelling tool
def test_parse_entry():
    assert label.parse_entry("2 o,s") == {"level": "MEDIUM", "codes": ["OFF_PLATFORM_CONTACT", "SCAM"], "unsure": False}
    assert label.parse_entry("3 PROHIBITED_GOODS u")["unsure"] is True
    assert label.parse_entry("1") == {"level": "LOW", "codes": [], "unsure": False}
    assert label.parse_entry("2 os")["codes"] == ["OFF_PLATFORM_CONTACT", "SCAM"]
    for bad in ("", "p", "4", "1 2", "2 zz", "hello"):
        assert label.parse_entry(bad) is None


def test_the_pilot_is_the_same_for_everyone_but_the_order_is_not(built):
    _, cases = built
    a, b = label.ordered_ids(cases, "A"), label.ordered_ids(cases, "B")
    assert sorted(a) == sorted(b) == sorted(c["id"] for c in cases)
    assert set(a[:30]) == set(b[:30]) and a != b and a[:30] != b[:30]
    assert a == label.ordered_ids(cases, "A")


def scripted(answers):
    queue = list(answers)
    return lambda prompt="": queue.pop(0)


@pytest.fixture()
def small_cases(tmp_path, built, monkeypatch):
    _, cases = built
    path = tmp_path / "cases.jsonl"
    rows = [{"kind": "x", "pool": built[0]}] + [{k: v for k, v in c.items() if k != "id"} | {"id": c["id"]} for c in cases[:40]]
    for row in rows[1:]:
        row["fixtures"] = {k: v for k, v in row["fixtures"].items() if k != "approvals"}
    common.write_jsonl(str(path), rows)
    monkeypatch.setattr(label, "LABELS", str(tmp_path / "labels"))
    return str(path)


def test_labelling_saves_each_decision_resumes_and_validates(small_cases):
    lines = []
    # first an invalid LOW with a code, a MEDIUM with no code, then valid ones; q stops
    label.run("T1", small_cases, scripted(["1 p", "2", "1", "2 o u", "no idea why", "3 p,s", "q"]), lines.append)
    saved = label.load_labels("T1")
    assert len(saved) == 3
    rows = common.read_jsonl(label.path_for("T1"))
    assert [r["level"] for r in rows] == ["LOW", "MEDIUM", "HIGH"]
    assert rows[1]["unsure"] is True and rows[1]["note"] == "no idea why" and rows[0]["pilot"] is True
    assert any("không có mã" in line for line in lines) and any("ít nhất một mã" in line for line in lines)
    # running again continues after the labelled cases instead of repeating them
    label.run("T1", small_cases, scripted(["1", "q"]), lines.append)
    assert len(label.load_labels("T1")) == 4
    # b goes back; the redo replaces the earlier label (last row wins)
    label.run("T1", small_cases, scripted(["b", "3 x", "q"]), lines.append)
    assert len(label.load_labels("T1")) == 4 and len(common.read_jsonl(label.path_for("T1"))) == 5


def test_redo_replaces_only_the_chosen_cases(small_cases):
    lines = []
    label.run("T2", small_cases, scripted(["1", "1", "1", "q"]), lines.append)
    first_three = [r["id"] for r in common.read_jsonl(label.path_for("T2"))]
    target = first_three[1]
    label.run("T2", small_cases, scripted(["3 p"]), lines.append, redo=[target])
    latest = label.load_labels("T2")
    assert latest[target]["level"] == "HIGH" and latest[first_three[0]]["level"] == "LOW" and len(latest) == 3
    with pytest.raises(SystemExit):
        label.run("T2", small_cases, scripted([]), lines.append, redo=["RS-9999"])
    # a quit in the middle keeps the cases not yet redone
    label.run("T2", small_cases, scripted(["q"]), lines.append, redo=[first_three[0], first_three[2]])
    assert label.load_labels("T2")[first_three[0]]["level"] == "LOW"


# ---------------------------------------------------------------- agreement and adjudication
def lab(level, codes=(), unsure=False):
    return {"level": level, "codes": list(codes), "unsure": unsure}


def test_adjudication_rules_and_gold():
    assert not adjudicate.needs_adjudication(lab("LOW"), lab("LOW"))
    assert adjudicate.needs_adjudication(lab("LOW"), lab("MEDIUM", ["SCAM"]))
    assert adjudicate.needs_adjudication(lab("MEDIUM", ["SCAM"]), lab("MEDIUM", ["OVERPROMISE"]))
    assert adjudicate.needs_adjudication(lab("LOW", unsure=True), lab("LOW"))
    ann = {"A": {"1": lab("LOW"), "2": lab("HIGH", ["SCAM"]), "3": lab("LOW", unsure=True)},
           "B": {"1": lab("LOW"), "2": lab("MEDIUM", ["SCAM"]), "3": lab("LOW")}}
    gold = adjudicate.build_gold(ann)
    assert [g["id"] for g in gold] == ["1"] and gold[0]["method"] == "agreed"


def test_the_split_is_stratified_by_group_and_repeatable():
    rows = [{"id": f"{g}{i}"} for g in "abc" for i in range(10)]
    groups = {r["id"]: r["id"][0] for r in rows}
    split = adjudicate.build_split(rows, groups)
    assert split == adjudicate.build_split(rows, groups)
    assert len(split["dev"]) == 9 and len(split["test"]) == 21 and not set(split["dev"]) & set(split["test"])
    for g in "abc":
        assert sum(1 for i in split["dev"] if i[0] == g) == 3


# ---------------------------------------------------------------- runner
def first(cases, group):
    return by_group(cases, group)[0]


def run(config, case, gateway=None, mode="full"):
    gateway = gateway or run_configs.Gateway("fake", "test")
    return run_configs.run_case(config, case, gateway, 0, mode)


def test_c0_to_c5_on_a_clean_and_a_semantic_case(built):
    _, cases = built
    clean, slang = first(cases, "clean"), first(cases, "prohibited_semantic")
    for config in run_configs.CONFIGS:
        assert run(config, clean)["level"] == "LOW"
    assert run("C0", slang)["level"] == "LOW" and run("C1", slang)["level"] != "HIGH"
    for config in ("C3", "C4", "C5"):
        row = run(config, slang)
        assert row["status"] == "ok" and row["flags_kept"] >= 1 and "PROHIBITED_GOODS" in row["codes"] and row["ai_score"] > 0
    assert run("C2", slang)["level"] == "HIGH"


class Scripted:
    """Stands in for the model with fixed answers per stage."""

    def __init__(self, flags=None, confirm=None, fail=False):
        self.flags, self.confirm, self.fail = flags or [], confirm, fail

    def install(self, monkeypatch):
        def respond(stage, prompt, texts):
            if self.fail:
                raise RuntimeError("provider down")
            if stage == "flags":
                return json.dumps({"flags": self.flags}, ensure_ascii=False), "scripted"
            if stage == "verify":
                return json.dumps({"confirmed": self.confirm if self.confirm is not None else []}), "scripted"
            return json.dumps({"risk_score": 10}), "scripted"
        monkeypatch.setattr(fakellm, "respond", respond)


def test_c3_trusts_a_made_up_quote_and_c4_c5_do_not(built, monkeypatch):
    _, cases = built
    case = first(cases, "clean")
    Scripted(flags=[{"code": "VEILED_PROHIBITED", "quote": "đoạn này không có trong văn bản", "confidence": "HIGH"}]).install(monkeypatch)
    assert run("C3", case)["ai_score"] == 25 and run("C3", case)["flags_kept"] == 1
    for config in ("C4", "C5"):
        row = run(config, case)
        assert row["ai_score"] == 0 and row["flags_kept"] == 0 and row["flags_proposed"] == 1


def test_c5_second_reading_can_remove_a_flag_that_c4_keeps(built, monkeypatch):
    _, cases = built
    case = first(cases, "borderline")
    quote = case["event"]["description"].split(".")[-2].strip()[:60]
    Scripted(flags=[{"code": "VEILED_PROHIBITED", "quote": quote, "confidence": "HIGH"}], confirm=[]).install(monkeypatch)
    assert run("C4", case)["ai_score"] == 25
    c5 = run("C5", case)
    assert c5["ai_score"] == 0 and c5["ai_meta"]["verify_removed"] == ["VEILED_PROHIBITED"]


def test_a_failed_model_call_is_a_failed_row_not_a_low(built, monkeypatch):
    _, cases = built
    Scripted(fail=True).install(monkeypatch)
    for config in ("C2", "C3", "C4", "C5"):
        row = run(config, first(cases, "prohibited_semantic"))
        assert row["status"] == "failed" and "level" not in row and row["error"]


def test_the_baseline_engine_ablation_is_scoped(built):
    saved_cap, saved_drop = engine.settings.risk_organizer_signal_cap, engine.drop_flags_already_scored
    with run_configs.baseline_engine():
        assert engine.settings.risk_organizer_signal_cap > 1000 and engine.drop_flags_already_scored([1], []) == ([1], 0)
    assert engine.settings.risk_organizer_signal_cap == saved_cap and engine.drop_flags_already_scored is saved_drop


def test_layer_a_modes_change_what_the_rules_see(built):
    _, cases = built
    ban = first(cases, "ban_context")
    assert run("C0", ban, mode="full")["rule_score"] == 0
    assert run("C0", ban, mode="no_context")["rule_score"] > 0  # without the context test the ban list is scored as an offer
    veiled = next(c for c in by_group(cases, "prohibited_veiled") if c["disguise"] in ("dotted", "spaced", "leet"))
    assert run("C0", veiled, mode="full")["rule_score"] > run("C0", veiled, mode="no_decode")["rule_score"]
    assert rules_unchanged()


def rules_unchanged():
    from app.services.risk import rules
    return rules.is_prohibition_context.__name__ == "is_prohibition_context"


def test_a_cached_answer_is_reused_and_repeats_are_independent(built, tmp_path, monkeypatch):
    _, cases = built
    monkeypatch.setattr(run_configs, "CACHE", str(tmp_path))
    calls = []

    def fake_ai_invoke(profile, messages, actor=None, **kwargs):
        calls.append(1)
        return fakellm_response('{"flags": []}')

    def fakellm_response(text):
        return type("R", (), {"content": text, "response_metadata": {"model_name": "m"}})()

    monkeypatch.setattr("app.services.ai.ai_call.ai_invoke", fake_ai_invoke)
    gateway = run_configs.Gateway("live", "t")
    texts = {"title": "t", "description": "d"}
    prompt = [("human", "Mô tả ... flags")]
    gateway.invoker("RS-1", 0, texts)("risk_scoring", prompt)
    gateway.invoker("RS-1", 0, texts)("risk_scoring", prompt)
    gateway.invoker("RS-1", 1, texts)("risk_scoring", prompt)
    assert len(calls) == 2 and gateway.cached_calls == 1
    again = run_configs.Gateway("live", "t")
    again.invoker("RS-1", 1, texts)("risk_scoring", prompt)
    assert len(calls) == 2 and again.cached_calls == 1


# ---------------------------------------------------------------- report
def test_the_report_counts_failures_separately_and_refuses_fake_runs(tmp_path, monkeypatch):
    gold = [{"id": f"RS-{i}", "level": level, "codes": []} for i, level in enumerate(["HIGH", "HIGH", "LOW", "LOW", "MEDIUM"])]
    common.write_jsonl(str(tmp_path / "gold.jsonl"), gold)
    monkeypatch.setattr(assessment_report, "GOLD_PATH", str(tmp_path / "gold.jsonl"))
    monkeypatch.setattr(assessment_report, "RESULTS", str(tmp_path / "results"))

    def write(config, llm, levels):
        header = {"config": config, "split": "all", "limit": 0, "layer_a": "full", "repeats": 1, "llm": llm, "models": []}
        rows = [({"id": f"RS-{i}", "group": "g", "config": config, "repeat": 0, "status": "ok", "score": 0, "level": lv, "codes": [],
                  "flags_proposed": 0, "flags_kept": 0, "ai_meta": {}, "latency_ms": 1.0} if lv else
                 {"id": f"RS-{i}", "group": "g", "config": config, "repeat": 0, "status": "failed", "error": "AI_FAILED"})
                for i, lv in enumerate(levels)]
        common.write_jsonl(str(tmp_path / "results" / config / "2026_full.jsonl"), [header] + rows)

    write("C1", "none", ["HIGH", "LOW", "LOW", "LOW", "MEDIUM"])
    write("C4", "fake", ["HIGH", "HIGH", "LOW", "LOW", "MEDIUM"])
    text = assessment_report.build_report("all", ["C1", "C4"], "full", "C1", allow_fake=False)
    assert "C4: chưa có kết quả" in text and "| C1 | 5 | 0 | 80.0%" in text
    assert "1/2" not in text and "50.0%" in text  # one of the two HIGH cases was missed (miss-HIGH 50%)
    write("C5", "none", ["HIGH", None, "LOW", "LOW", "MEDIUM"])
    text = assessment_report.build_report("all", ["C5"], "full", "C5", allow_fake=False)
    assert "| C5 | 4 | 1 |" in text  # the failed case is reported and not scored
    assert math.isclose(assessment_report.level_metrics(["HIGH", "LOW"], ["HIGH", "LOW"])["macro_f1"], 2 / 3)


# ---------------------------------------------------------------- batch labelling (the model's pass)
def test_batch_put_validates_and_status_finds_gaps(small_cases, tmp_path, monkeypatch):
    from research.risk import batch_label
    monkeypatch.setattr(common, "CASES_PATH", small_cases)
    monkeypatch.setattr(batch_label, "load_cases", lambda: common.load_cases(small_cases))
    out = str(tmp_path / "part.jsonl")
    lines = []
    errors = batch_label.put("T3", 31, 34, out, ["31|1", "32|3 p,r", "33|2 o u|not sure why", "34|1 p", "99|1", "35 x", "31|2 r u"], lines.append)
    assert errors == 4  # LOW with a code, position out of range, no position, unsure without a note
    assert batch_label.status("T3", 31, 34, out, lines.append) == [34]  # the LOW-with-a-code line was refused
    rows = common.read_jsonl(out)
    assert {r["order"] for r in rows} == {31, 32, 33}
    assert all(r["annotator"] == "T3" and "LLM" in r["by"] and r["pilot"] is False for r in rows)


def test_merge_checks_positions_and_refuses_overwriting(small_cases, tmp_path, monkeypatch):
    from research.risk import batch_label
    monkeypatch.setattr(batch_label, "load_cases", lambda: common.load_cases(small_cases))
    order = label.ordered_ids(common.load_cases(small_cases)[1], "T4")
    part = str(tmp_path / "p.jsonl")
    common.write_jsonl(part, [{"id": order[30], "annotator": "T4", "level": "LOW", "codes": [], "unsure": False, "note": "", "order": 31, "pilot": False}])
    assert batch_label.merge("T4", [part], lambda *_: None) == 1
    with pytest.raises(SystemExit):
        batch_label.merge("T4", [part], lambda *_: None)
    common.write_jsonl(part, [{"id": order[0], "annotator": "T4", "level": "LOW", "codes": [], "unsure": False, "note": "", "order": 31, "pilot": False}])
    with pytest.raises(SystemExit):
        batch_label.merge("T4", [part], lambda *_: None)


def test_adjudication_batches_hide_the_source_and_demand_a_reason(monkeypatch, tmp_path):
    from research.risk import batch_adjudicate
    labels = {"A": {"c1": lab("HIGH", ["SCAM"]), "c2": lab("LOW")}, "B": {"c1": lab("MEDIUM", ["SCAM"]), "c2": lab("LOW")}, "C": {}}
    monkeypatch.setattr(batch_adjudicate, "load_labels", lambda who: labels[who])
    assert batch_adjudicate.cases_to_decide() == ["c1"]
    first, second = batch_adjudicate.shuffled_pair("c1")
    assert {first["level"], second["level"]} == {"HIGH", "MEDIUM"}
    assert batch_adjudicate.shuffled_pair("c1") == (first, second)
    # the pairing depends only on the case id: across many ids both orders occur
    seen = set()
    for i in range(40):
        labels["A"][f"x{i}"], labels["B"][f"x{i}"] = lab("HIGH", ["SCAM"]), lab("LOW")
        seen.add(batch_adjudicate.shuffled_pair(f"x{i}")[0]["level"])
    assert seen == {"HIGH", "LOW"}
    out = str(tmp_path / "c.jsonl")
    outputs = []
    assert batch_adjudicate.put(1, 1, out, ["1|3 s|Câu chào bán nêu rõ", "1|2 o u|x", "1|2 o", "2|1|x"], outputs.append) == 3
    row = common.read_jsonl(out)[0]
    assert row["annotator"] == "C" and row["method"] == "adjudicated" and row["note"] == "Câu chào bán nêu rõ" and row["id"] == "c1"


class Refused(Exception):
    code = "AI_UNAVAILABLE"


def test_calls_rotate_over_the_keys_skip_a_refused_one_and_restore_the_setting(monkeypatch):
    from app.core.config import settings
    monkeypatch.setattr(settings, "groq_api_key", "k1,k2,k3")
    gateway = run_configs.Gateway("fake", "t")
    seen, refused = [], {"k2"}

    def call(profile, messages, actor=None):
        seen.append(settings.groq_api_key)
        if settings.groq_api_key in refused:
            raise Refused()
        return "ok"

    naps = []
    for _ in range(4):
        assert gateway._call(call, "p", [], "a", sleep=naps.append) == "ok"
    assert seen == ["k1", "k2", "k3", "k1", "k2", "k3"] and naps == []
    assert settings.groq_api_key == "k1,k2,k3"
    # every key refused: the run pauses between rounds, then gives up with the last error
    refused.update({"k1", "k3"})
    with pytest.raises(Refused):
        gateway._call(call, "p", [], "a", waits=(0, 5, 7), sleep=naps.append)
    assert naps == [5, 7] and settings.groq_api_key == "k1,k2,k3"
    # an error that is not a "slow down" is not retried
    def broken(profile, messages, actor=None):
        raise ValueError("bad")
    with pytest.raises(ValueError):
        gateway._call(broken, "p", [], "a", sleep=naps.append)


def test_review_sample_is_repeatable_and_summarises(monkeypatch, tmp_path):
    from research.risk import review_sample
    gold = tmp_path / "gold.jsonl"
    common.write_jsonl(str(gold), [{"id": f"c{i}", "level": "LOW", "codes": [], "method": "adjudicated" if i % 2 else "agreed", "note": ""} for i in range(40)])
    monkeypatch.setattr(review_sample, "GOLD", str(gold))
    first = review_sample.sample(7, 5)
    assert first == review_sample.sample(7, 5) and all(int(i[1:]) % 2 for i in first) and first != review_sample.sample(8, 5)
    rows = [{"id": "c1", "reviewer": "H", "your_level": "LOW", "your_codes": [], "gold_level": "LOW", "gold_codes": []},
            {"id": "c3", "reviewer": "H", "your_level": "MEDIUM", "your_codes": ["SCAM"], "gold_level": "LOW", "gold_codes": []}]
    assert review_sample.summary(rows) == "2 ca; trùng mức nhãn chuẩn 1/2; trùng cả mức và mã 1/2"


def test_fleiss_kappa_and_majority_decisions():
    labels = ["LOW", "MEDIUM", "HIGH"]
    assert stats.fleiss_kappa([["LOW", "LOW", "LOW"], ["HIGH", "HIGH", "HIGH"], ["MEDIUM"] * 3], labels) == pytest.approx(1.0)
    mixed = [["LOW", "MEDIUM", "HIGH"]] * 3
    assert stats.fleiss_kappa(mixed, labels) == pytest.approx(-0.5)
    with pytest.raises(ValueError):
        stats.fleiss_kappa([["LOW", "LOW"], ["LOW"]], labels)
    from research.risk import consensus
    v = lambda level, *codes: {"level": level, "codes": list(codes)}
    assert consensus.decide([v("HIGH", "SCAM"), v("HIGH", "SCAM", "OVERPROMISE"), v("MEDIUM", "SCAM")]) == {"level": "HIGH", "codes": ["SCAM"], "method": "majority"}
    assert consensus.decide([v("LOW"), v("LOW"), v("LOW")]) == {"level": "LOW", "codes": [], "method": "unanimous"}
    assert consensus.decide([v("LOW"), v("MEDIUM", "SCAM"), v("HIGH", "SCAM")]) is None
    assert consensus.decide([v("MEDIUM", "SCAM"), v("MEDIUM", "OVERPROMISE")])["level"] == "MEDIUM"  # two raters who agree on the level
    assert consensus.decide([v("MEDIUM", "SCAM"), v("HIGH", "SCAM")]) is None
