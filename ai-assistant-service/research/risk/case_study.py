"""Case study (paper section IV.G): the production path (C5) on one event, with the trace of every step and its time.
    python -m research.risk.case_study --file event.txt [--organizer "Tên ban tổ chức"] [--llm live|fake]

event.txt is UTF-8; the first line is the title, the rest is the description. The signals that need other data (organiser
account, price of the category, similar events) are reported as not assessed, because a text file does not carry that data.
Model answers are cached in cache/case-study.jsonl (a rerun costs nothing and gives the same trace)."""
import argparse
import hashlib
import json
import time

from app.services.risk import content, engine, rules, signals
from research.risk.common import FIXED_NOW
from research.risk.run_configs import ACTOR, Gateway, layer_a_mode


def _unavailable(*_args):
    raise LookupError("not available from a text file")


def trace(title: str, description: str, organizer: str, llm: str) -> dict:
    event = {"id": "case-study", "title": title, "description": description, "organizerName": organizer}
    built = content.build(event)
    texts = built["texts"]
    steps, started = {}, time.perf_counter()

    with layer_a_mode("full") as ruleset:
        t = time.perf_counter()
        found = rules.evaluate(texts, ruleset)
        steps["rules_ms"] = round((time.perf_counter() - t) * 1000, 1)

        t = time.perf_counter()
        source = signals.SignalSource(user=_unavailable, approvals=lambda: [], category_prices=_unavailable)
        results = signals.evaluate(event, texts, source, FIXED_NOW)
        # A text file has no dates, no similar events and no organiser data, so a signal that "assessed" it would only be reading
        # the gaps (no start time reads as a schedule anomaly). Every signal is reported as not assessed.
        results = [{"name": r["name"], "status": "not_assessed"} for r in results]
        steps["signals_ms"] = round((time.perf_counter() - t) * 1000, 1)

        gateway = Gateway(llm, "case-study")
        # the cache key carries a hash of the text: another text (or an edited one) must never reuse an old answer
        case_id = "case-" + hashlib.sha256((texts["title"] + "\n" + texts["description"]).encode("utf-8")).hexdigest()[:12]
        invoke = gateway.invoker(case_id, 0, texts)
        from app.services.admin_intel import risk_service
        t = time.perf_counter()
        state, flags, meta = risk_service._run_ai(built, ACTOR, invoke, None)
        steps["model_wall_ms"] = round((time.perf_counter() - t) * 1000, 1)
        steps["model_live_ms"] = round(gateway.live_ms, 1)
        steps["model_live_calls"], steps["model_cached_calls"] = gateway.live_calls, gateway.cached_calls

        t = time.perf_counter()
        result = engine.combine(found, results, state, flags, ruleset.version, meta)
        steps["combine_ms"] = round((time.perf_counter() - t) * 1000, 1)
    steps["total_ms"] = round((time.perf_counter() - started) * 1000, 1)
    return {"description_chars": len(texts["description"]), "title_chars": len(texts["title"]), "ai_state": state, "ai_meta": meta,
            "flags_kept": [{"code": f["code"], "confidence": f["confidence"], "quote": f["evidence"]["text"]} for f in flags],
            "rule_findings": [{"code": f["code"], "weight": f["weight"], "severity": f.get("severity"), "evidence": [e.get("text") for e in f.get("evidence", [])][:5]}
                              for f in found],
            "signals": [{"name": r["name"], "status": r["status"], "finding": (r.get("finding") or {}).get("code")} for r in results],
            "risk_score": result["risk_score"], "risk_level": result["risk_level"], "complete": result["complete"],
            "coverage": {k: v for k, v in result["coverage"].items() if k != "ai_meta"},
            "reasons": [{"layer": r["layer"], "code": r["code"], "weight": r["weight"], "status": r.get("status", "assessed")} for r in result["reason_items"]],
            "steps": steps}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--file", required=True)
    parser.add_argument("--organizer", default="")
    parser.add_argument("--llm", choices=["live", "fake"], default="live")
    parser.add_argument("--json", help="also write the trace to this file")
    args = parser.parse_args()
    with open(args.file, encoding="utf-8-sig") as handle:
        title, _, description = handle.read().partition("\n")
    out = trace(title.strip(), description.strip(), args.organizer, args.llm)
    print(json.dumps(out, ensure_ascii=False, indent=2))
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(out, handle, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
