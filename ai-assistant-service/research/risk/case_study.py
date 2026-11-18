"""Nghiên cứu tình huống (bài báo mục IV.G): đường production (C5) trên một sự kiện, kèm vết của từng bước và thời gian.
    python -m research.risk.case_study --file event.txt [--organizer "Tên ban tổ chức"] [--llm live|fake]

event.txt là UTF-8; dòng đầu là tên, phần còn lại là mô tả. Các tín hiệu cần dữ liệu khác (tài khoản ban tổ chức,
giá của thể loại, sự kiện tương tự) được báo là không đánh giá, vì tệp văn bản không mang dữ liệu đó.
Câu trả lời của mô hình được lưu trong cache/case-study.jsonl (chạy lại không tốn gì và cho cùng một vết)."""
import argparse
import hashlib
import json
import time

from app.services.risk import content, engine, rules, signals
from research.risk.common import FIXED_NOW
from research.risk.run_configs import ACTOR, Gateway, layer_a_mode


def _unavailable(*_args):
    """Thay thế cho bộ đọc dữ liệu: luôn ném LookupError vì tệp văn bản không có dữ liệu đó."""
    raise LookupError("not available from a text file")


def trace(title: str, description: str, organizer: str, llm: str) -> dict:
    """Chạy lớp luật, lớp tín hiệu, lớp mô hình và bước kết hợp trên một sự kiện, đo thời gian từng bước; trả vết gồm điểm, mức, cờ, lý do và thời gian."""
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
        # Tệp văn bản không có ngày, không có sự kiện tương tự và không có dữ liệu ban tổ chức, nên tín hiệu nào "đánh giá" được cũng chỉ đang đọc
        # các chỗ trống (không có giờ bắt đầu bị đọc là lịch bất thường). Mọi tín hiệu đều được báo là không đánh giá.
        results = [{"name": r["name"], "status": "not_assessed"} for r in results]
        steps["signals_ms"] = round((time.perf_counter() - t) * 1000, 1)

        gateway = Gateway(llm, "case-study")
        # khóa cache chứa mã băm của văn bản: văn bản khác (hoặc đã sửa) không bao giờ được dùng lại câu trả lời cũ
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
    """Điểm vào dòng lệnh: đọc tệp sự kiện, chạy vết và in JSON (ghi thêm ra --json nếu có)."""
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
