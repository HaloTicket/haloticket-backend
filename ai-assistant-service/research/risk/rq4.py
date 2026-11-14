"""RQ4: what each step of layer A contributes. RISK_SET=t2 python -m research.risk.rq4

C0 (rules only) and C1 (rules and signals) on the 500 cases of t2 in the four layer-A modes (run_configs --layer-a): full, without the
prohibition-context test, without the decoding of veiled text, without both. One run per mode; no interval is computed."""
import glob
import json

from research.risk.common import GOLD_PATH, LEVELS, RESULTS, read_jsonl
from research.risk.stats import macro_f1

MODES = (("full", "Đầy đủ"), ("no_context", "Tắt ngữ cảnh cấm"), ("no_decode", "Tắt giải mã chữ che"), ("bare", "Tắt cả hai"))


def run_rows(config: str, mode: str) -> dict:
    pattern = "*_full.jsonl" if mode == "full" else f"*_{mode}.jsonl"
    for path in sorted(glob.glob(f"{RESULTS}/{config}/{pattern}"), reverse=True):
        with open(path, encoding="utf-8") as handle:
            lines = [json.loads(line) for line in handle if line.strip()]
        if lines[0].get("layer_a") == mode and lines[0].get("repeats", 1) == 1 and lines[0].get("split") == "all":
            return {r["id"]: r for r in lines[1:] if r.get("status") == "ok"}
    raise SystemExit(f"không có kết quả {config}/{mode}")


def pct(value: float) -> str:
    return f"{value * 100:.1f}%".replace(".", ",")


def table() -> str:
    gold = {r["id"]: r["level"] for r in read_jsonl(GOLD_PATH)}
    lines = ["| Chế độ | C0 chính xác | C0 macro F1 | C0 HIGH thành LOW | C0 báo sai HIGH | C1 chính xác | C1 macro F1 | C1 HIGH thành LOW | C1 báo sai HIGH |",
             "|:--|:--|:--|:--|:--|:--|:--|:--|:--|"]
    for mode, name in MODES:
        cells = []
        for config in ("C0", "C1"):
            rows = run_rows(config, mode)
            ids = [i for i in gold if i in rows]
            g, p = [gold[i] for i in ids], [rows[i]["level"] for i in ids]
            high = [i for i in ids if gold[i] == "HIGH"]
            other = [i for i in ids if gold[i] != "HIGH"]
            cells += [pct(sum(a == b for a, b in zip(g, p)) / len(ids)), pct(macro_f1(g, p, LEVELS)),
                      pct(sum(rows[i]["level"] == "LOW" for i in high) / len(high)),
                      pct(sum(rows[i]["level"] == "HIGH" for i in other) / len(other))]
        lines.append(f"| {name} | " + " | ".join(cells) + " |")
    return "\n".join(lines)


if __name__ == "__main__":
    print(table())
