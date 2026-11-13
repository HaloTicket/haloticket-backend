"""A human check of the adjudicated labels: python -m research.risk.review_sample [--n 20] [--seed 7] [--annotator H]

Draws a seeded sample of the cases the model adjudicators decided, shows each one WITHOUT any label, asks for your own label
(same syntax as label.py), and only then reveals the gold label and the adjudicator's reason. Answers go to
data/review_adjudication.jsonl; the summary at the end tells how often a person reading the case alone reaches the gold level.
Run it again to continue; the sample is the same for the same seed."""
import argparse
import os
import random

from research.risk.common import DATA, append_jsonl, load_cases, now_iso, read_jsonl
from research.risk.label import ask_label, latest
from research.risk.view import render

GOLD = os.path.join(DATA, "gold.jsonl")
OUT = os.path.join(DATA, "review_adjudication.jsonl")


def sample(seed: int, n: int) -> list:
    adjudicated = sorted(r["id"] for r in read_jsonl(GOLD) if r["method"] == "adjudicated")
    random.Random(seed).shuffle(adjudicated)
    return adjudicated[:n]


def summary(rows: list) -> str:
    rows = list(latest(rows).values())
    if not rows:
        return "chưa có câu trả lời nào"
    level = sum(1 for r in rows if r["your_level"] == r["gold_level"])
    full = sum(1 for r in rows if r["your_level"] == r["gold_level"] and sorted(r["your_codes"]) == sorted(r["gold_codes"]))
    return f"{len(rows)} ca; trùng mức nhãn chuẩn {level}/{len(rows)}; trùng cả mức và mã {full}/{len(rows)}"


def run(n: int, seed: int, annotator: str, input_fn=input, output=print) -> None:
    gold = {r["id"]: r for r in read_jsonl(GOLD)}
    _, cases = load_cases()
    by_id = {c["id"]: c for c in cases}
    done = latest([r for r in read_jsonl(OUT) if r["reviewer"] == annotator])
    todo = [cid for cid in sample(seed, n) if cid not in done]
    for position, cid in enumerate(todo, 1):
        output("\n" + "=" * 78 + f"\nCa {position}/{len(todo)} (chưa hiện nhãn)\n" + "-" * 78)
        output(render(by_id[cid], full_similar=True))
        output("-" * 78)
        entry = ask_label(input_fn, output, allow_back=False)
        if entry == "q":
            break
        g = gold[cid]
        output(f"Nhãn chuẩn: {g['level']} {','.join(g['codes']) or '-'}   (phân xử: {g.get('note', '')})")
        append_jsonl(OUT, {"id": cid, "reviewer": annotator, "your_level": entry["level"], "your_codes": entry["codes"], "unsure": entry["unsure"],
                           "note": entry["note"], "gold_level": g["level"], "gold_codes": g["codes"], "reviewed_at": now_iso()})
    output("\n" + summary([r for r in read_jsonl(OUT) if r["reviewer"] == annotator]))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--n", type=int, default=20)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--annotator", default="H")
    args = parser.parse_args()
    run(args.n, args.seed, args.annotator)


if __name__ == "__main__":
    main()
