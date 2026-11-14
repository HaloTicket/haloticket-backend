"""Stability over repeated runs (RQ3). RISK_SET=t2 python -m research.risk.stability

For C2 to C5 run with --repeats 3: mean SD of the case score (sample SD over the repeats, as research.risk.assessment_report.stability),
cases whose level changes between repeats, cases that flip between HIGH and LOW, and the macro F1 of each repeat with its SD."""
import glob
import json
import os
import statistics
from collections import defaultdict

from research.risk import assessment_report, stats
from research.risk.common import GOLD_PATH, LEVELS, RESULTS, read_jsonl

NAMES = {"C2": "C2 chấm trực tiếp", "C3": "C3 cờ không kiểm trích dẫn", "C4": "C4 cờ kiểm trích dẫn", "C5": "C5 đường chạy thật"}


def repeat_rows(config: str, repeats: int = 3) -> list:
    for path in sorted(glob.glob(os.path.join(RESULTS, config, "*_full.jsonl")), reverse=True):
        with open(path, encoding="utf-8") as handle:
            lines = [json.loads(line) for line in handle if line.strip()]
        if lines[0].get("repeats") == repeats and lines[0].get("llm") == "live" and lines[0].get("layer_a") == "full":
            return [r for r in lines[1:] if r.get("status") == "ok"]
    raise SystemExit(f"không có lần chạy k={repeats} của {config}")


def compute(config: str) -> dict:
    gold = {r["id"]: r["level"] for r in read_jsonl(GOLD_PATH)}
    rows = repeat_rows(config)
    summary = assessment_report.stability(rows)
    by_case = defaultdict(list)
    for r in rows:
        by_case[r["id"]].append(r)
    flips = sum(1 for v in by_case.values() if {"HIGH", "LOW"} <= {x["level"] for x in v})
    f1 = []
    for repeat in sorted({r["repeat"] for r in rows}):
        chosen = [r for r in rows if r["repeat"] == repeat and r["id"] in gold]
        f1.append(stats.macro_f1([gold[r["id"]] for r in chosen], [r["level"] for r in chosen], LEVELS) * 100)
    return {"config": config, "cases": summary["cases"], "mean_sd": summary["mean_sd"], "changed": summary["changed"], "flips": flips,
            "f1": f1, "f1_sd": statistics.stdev(f1)}


def comma(value: float, digits: int) -> str:
    return f"{value:.{digits}f}".replace(".", ",")


def row(result: dict) -> str:
    share = result["changed"] / result["cases"] * 100
    return (f"| {NAMES[result['config']]} | {comma(result['mean_sd'], 2)} | {result['changed']} ({comma(share, 1)}%) | {result['flips']} | "
            f"{'; '.join(comma(x, 1) for x in result['f1'])} | {comma(result['f1_sd'], 2)} |")


def table() -> str:
    header = ["| Cấu hình | SD điểm trung bình | Ca đổi mức giữa các lần | Ca lật giữa HIGH và LOW | Macro F1 từng lần (%) | SD của macro F1 |",
              "|:--|--:|:--|--:|:--|--:|"]
    return "\n".join(header + [row(compute(c)) for c in NAMES])


if __name__ == "__main__":
    print(table())
