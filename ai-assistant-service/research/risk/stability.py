"""Độ ổn định qua các lần chạy lặp (RQ3). RISK_SET=t2 python -m research.risk.stability

Với C2 đến C5 chạy với --repeats 3: SD trung bình của điểm ca (SD mẫu qua các lần lặp, như research.risk.assessment_report.stability),
số ca đổi mức giữa các lần lặp, số ca lật giữa HIGH và LOW, và macro F1 của từng lần lặp kèm SD của nó.
"""
import glob
import json
import os
import statistics
from collections import defaultdict

from research.risk import assessment_report, stats
from research.risk.common import GOLD_PATH, LEVELS, RESULTS, read_jsonl

NAMES = {"C2": "C2 chấm trực tiếp", "C3": "C3 cờ không kiểm trích dẫn", "C4": "C4 cờ kiểm trích dẫn", "C5": "C5 đường chạy thật"}


def repeat_rows(config: str, repeats: int = 3) -> list:
    """Lấy các dòng kết quả (trạng thái ok) của lần chạy lặp mới nhất của một cấu hình: đúng số lần lặp, mô hình thật, lớp A đầy đủ.

    Raises:
        SystemExit: khi không có lần chạy phù hợp.
    """
    for path in sorted(glob.glob(os.path.join(RESULTS, config, "*_full.jsonl")), reverse=True):
        with open(path, encoding="utf-8") as handle:
            lines = [json.loads(line) for line in handle if line.strip()]
        if lines[0].get("repeats") == repeats and lines[0].get("llm") == "live" and lines[0].get("layer_a") == "full":
            return [r for r in lines[1:] if r.get("status") == "ok"]
    raise SystemExit(f"không có lần chạy k={repeats} của {config}")


def compute(config: str) -> dict:
    """Tính các số đo độ ổn định của một cấu hình: số ca, SD điểm trung bình, số ca đổi mức, số ca lật HIGH và LOW, macro F1 từng lần lặp và SD của chúng."""
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
    """Định dạng số với số chữ số thập phân cho trước, dùng dấu phẩy thập phân."""
    return f"{value:.{digits}f}".replace(".", ",")


def row(result: dict) -> str:
    """Dựng một dòng bảng Markdown từ kết quả của compute."""
    share = result["changed"] / result["cases"] * 100
    return (f"| {NAMES[result['config']]} | {comma(result['mean_sd'], 2)} | {result['changed']} ({comma(share, 1)}%) | {result['flips']} | "
            f"{'; '.join(comma(x, 1) for x in result['f1'])} | {comma(result['f1_sd'], 2)} |")


def table() -> str:
    """Dựng bảng Markdown độ ổn định của C2 đến C5."""
    header = ["| Cấu hình | SD điểm trung bình | Ca đổi mức giữa các lần | Ca lật giữa HIGH và LOW | Macro F1 từng lần (%) | SD của macro F1 |",
              "|:--|--:|:--|--:|:--|--:|"]
    return "\n".join(header + [row(compute(c)) for c in NAMES])


if __name__ == "__main__":
    print(table())
