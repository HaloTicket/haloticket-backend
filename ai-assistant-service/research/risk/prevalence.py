"""Precision of the HIGH level at other violation rates. RISK_SET=t2 python -m research.risk.prevalence

The t2 set has 30.8% HIGH cases, far more than a platform would see. The recall of HIGH (TPR) and the share of non-HIGH cases
raised to HIGH (FPR) do not depend on how many cases of each kind there are, so the precision at a rate pi is
    precision(pi) = TPR*pi / (TPR*pi + FPR*(1-pi)).
The interval takes the Wilson 95% bounds of TPR and FPR in the worst and best direction, so it is conservative.
It assumes the non-HIGH cases of t2 stand for the non-HIGH cases of the platform; t2 is generated, so this is an estimate that
depends on that assumption, not a measurement on real traffic."""
import glob
import json
import os

from research.risk.common import GOLD_PATH, RESULTS, read_jsonl
from research.risk.stats import wilson

RATES = (0.01, 0.05, 0.10)


def latest_rows(config: str) -> dict:
    for path in sorted(glob.glob(os.path.join(RESULTS, config, "*_full.jsonl")), reverse=True):
        with open(path, encoding="utf-8") as handle:
            lines = [json.loads(line) for line in handle if line.strip()]
        head = lines[0]
        live_ok = head.get("llm") == "live" or config in ("C0", "C1")
        if head.get("repeats") == 1 and live_ok and head.get("layer_a") == "full" and head.get("split") == "all":
            return {r["id"]: r for r in lines[1:] if r.get("status") == "ok"}
    raise SystemExit(f"không có lần chạy k=1 của {config}")


def precision(tpr: float, fpr: float, rate: float) -> float:
    denominator = tpr * rate + fpr * (1 - rate)
    return tpr * rate / denominator if denominator else float("nan")


def fmt(value: float) -> str:
    return f"{value * 100:.1f}%".replace(".", ",")


def table() -> str:
    gold = {r["id"]: r["level"] for r in read_jsonl(GOLD_PATH)}
    positives = [i for i, level in gold.items() if level == "HIGH"]
    negatives = [i for i, level in gold.items() if level != "HIGH"]
    lines = [f"Trong bộ t2, {len(positives)} trong {len(gold)} ca là HIGH ({fmt(len(positives) / len(gold))}); {len(negatives)} ca còn lại là LOW hoặc MEDIUM.", "",
             "| Cấu hình | Recall HIGH [Wilson 95%] | Báo sai HIGH [Wilson 95%] | Precision HIGH ở 1% | ở 5% | ở 10% |",
             "|:--|:--|:--|:--|:--|:--|"]
    for config in ("C0", "C1", "C2", "C3", "C4", "C5"):
        rows = latest_rows(config)
        tp = sum(rows[i]["level"] == "HIGH" for i in positives)
        fp = sum(rows[i]["level"] == "HIGH" for i in negatives)
        tpr, fpr = tp / len(positives), fp / len(negatives)
        (tl, th), (fl, fh) = wilson(tp, len(positives)), wilson(fp, len(negatives))
        cells = []
        for rate in RATES:
            low, high = precision(tl, fh, rate), precision(th, fl, rate)
            cells.append(f"{fmt(precision(tpr, fpr, rate))} [{fmt(low)}; {fmt(high)}]")
        lines.append(f"| {config} | {fmt(tpr)} [{fmt(tl)}; {fmt(th)}] | {fmt(fpr)} [{fmt(fl)}; {fmt(fh)}] | " + " | ".join(cells) + " |")
    return "\n".join(lines)


if __name__ == "__main__":
    print(table())
