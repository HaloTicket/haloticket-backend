"""Agreement and gold labels for three (or two) independent annotators:
RISK_SET=t2 python -m research.risk.consensus --annotators H1,H2,L [--write-gold]

Prints Fleiss' kappa for the level, Cohen's kappa for every pair, and per-code pairwise kappa. With --write-gold it writes
gold.jsonl: the level chosen by at least two annotators, and each code marked by at least two. A case where all annotators
gave different levels (or, with two annotators, any disagreement) has no majority and goes to data/<set>/undecided.json for a
separate reader. The annotators' own files are read, never changed."""
import argparse
import itertools
import json
import os

from research.risk.common import CODES, DATA, GOLD_PATH, LEVELS, load_cases, now_iso, write_jsonl
from research.risk.label import load_labels
from research.risk.stats import cohen_kappa, fleiss_kappa, kappa_band


def decide(votes: list) -> dict:
    """votes: [{"level", "codes"}] -> {"level", "codes", "method"} or None when no level has a majority."""
    need = len(votes) // 2 + 1
    levels = [v["level"] for v in votes]
    top = max(set(levels), key=levels.count)
    if levels.count(top) < need:
        return None
    marked = [c for c in CODES if sum(1 for v in votes if c in v["codes"]) >= need]
    codes = [] if top == "LOW" else marked
    if top != "LOW" and not codes:  # a level above LOW needs a code: take the codes the annotators of that level agree on
        pool = [v["codes"] for v in votes if v["level"] == top]
        codes = [c for c in CODES if sum(1 for p in pool if c in p) >= max(1, len(pool) // 2 + len(pool) % 2)] or sorted(pool[0])[:1]
    return {"level": top, "codes": codes, "method": "unanimous" if len(set(levels)) == 1 else "majority"}


def report(annotators: list, labels: dict, ids: list) -> str:
    lines = [f"{len(ids)} ca có đủ nhãn của {', '.join(annotators)}"]
    items = [[labels[a][i]["level"] for a in annotators] for i in ids]
    if len(annotators) > 2:
        k = fleiss_kappa(items, list(LEVELS))
        lines.append(f"Fleiss kappa (mức): {k:.3f} ({kappa_band(k)})")
    for a, b in itertools.combinations(annotators, 2):
        x, y = [labels[a][i]["level"] for i in ids], [labels[b][i]["level"] for i in ids]
        k = cohen_kappa(x, y, list(LEVELS))
        lines.append(f"{a} với {b}: trùng mức {sum(p == q for p, q in zip(x, y))}/{len(ids)}, kappa {k:.3f} ({kappa_band(k)}), tuyến tính {cohen_kappa(x, y, list(LEVELS), 'linear'):.3f}")
    for a, b in itertools.combinations(annotators, 2):
        per = {}
        for code in CODES:
            x = ["y" if code in labels[a][i]["codes"] else "n" for i in ids]
            y = ["y" if code in labels[b][i]["codes"] else "n" for i in ids]
            per[code] = round(cohen_kappa(x, y, ["y", "n"]), 2) if len(set(x)) > 1 or len(set(y)) > 1 else None
        lines.append(f"{a} với {b}, kappa từng mã: {per}")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--annotators", default="H1,H2,L")
    parser.add_argument("--write-gold", action="store_true")
    args = parser.parse_args()
    annotators = [a.strip() for a in args.annotators.split(",") if a.strip()]
    labels = {a: load_labels(a) for a in annotators}
    _, cases = load_cases()
    ids = sorted(c["id"] for c in cases if all(c["id"] in labels[a] for a in annotators))
    missing = {a: len(cases) - sum(1 for c in cases if c["id"] in labels[a]) for a in annotators}
    print("còn thiếu nhãn:", missing)
    if not ids:
        raise SystemExit("chưa có ca nào đủ nhãn của mọi người gán")
    print(report(annotators, labels, ids))
    if args.write_gold:
        gold, undecided = [], []
        for i in ids:
            votes = [{"level": labels[a][i]["level"], "codes": labels[a][i]["codes"]} for a in annotators]
            verdict = decide(votes)
            if verdict is None:
                undecided.append(i)
            else:
                gold.append({"id": i, **verdict, "votes": {a: labels[a][i]["level"] for a in annotators}})
        write_jsonl(GOLD_PATH, gold)
        with open(os.path.join(DATA, "undecided.json"), "w", encoding="utf-8") as handle:
            json.dump({"made_at": now_iso(), "annotators": annotators, "ids": undecided}, handle, ensure_ascii=False, indent=2)
        print(f"gold: {len(gold)} ca ({sum(1 for g in gold if g['method'] == 'unanimous')} nhất trí); chưa có đa số: {len(undecided)} ca -> {DATA}/undecided.json")


if __name__ == "__main__":
    main()
