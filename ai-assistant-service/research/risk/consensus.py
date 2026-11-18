"""Độ đồng thuận và nhãn vàng cho ba (hoặc hai) người gán nhãn độc lập:
RISK_SET=t2 python -m research.risk.consensus --annotators H1,H2,L [--write-gold]

In kappa Fleiss của mức rủi ro, kappa Cohen cho từng cặp và kappa từng mã. Với --write-gold thì ghi gold.jsonl:
mức do ít nhất hai người chọn, và mỗi mã có ít nhất hai người đánh dấu. Ca mà mọi người chọn mức khác nhau (hoặc, với hai
người, bất kỳ bất đồng nào) không có đa số và được ghi vào data/<set>/undecided.json cho một người đọc riêng. Tệp của từng
người gán chỉ được đọc, không bị sửa.
"""
import argparse
import itertools
import json
import os

from research.risk.common import CODES, DATA, GOLD_PATH, LEVELS, load_cases, now_iso, write_jsonl
from research.risk.label import load_labels
from research.risk.stats import cohen_kappa, fleiss_kappa, kappa_band


def decide(votes: list) -> dict:
    """Quyết định nhãn vàng của một ca theo đa số phiếu.

    Args:
        votes: danh sách phiếu, mỗi phiếu có "level" và "codes".

    Returns:
        {"level", "codes", "method"} (method là unanimous hoặc majority), hoặc None khi không mức nào đạt đa số.
    """
    need = len(votes) // 2 + 1
    levels = [v["level"] for v in votes]
    top = max(set(levels), key=levels.count)
    if levels.count(top) < need:
        return None
    marked = [c for c in CODES if sum(1 for v in votes if c in v["codes"]) >= need]
    codes = [] if top == "LOW" else marked
    if top != "LOW" and not codes:  # mức trên LOW cần có mã: lấy các mã mà những người chọn mức đó đồng ý
        pool = [v["codes"] for v in votes if v["level"] == top]
        codes = [c for c in CODES if sum(1 for p in pool if c in p) >= max(1, len(pool) // 2 + len(pool) % 2)] or sorted(pool[0])[:1]
    return {"level": top, "codes": codes, "method": "unanimous" if len(set(levels)) == 1 else "majority"}


def report(annotators: list, labels: dict, ids: list) -> str:
    """Dựng báo cáo đồng thuận dạng văn bản: kappa Fleiss (khi hơn hai người), kappa Cohen từng cặp theo mức (thường và tuyến tính) và kappa từng mã.

    Args:
        annotators: tên những người gán nhãn.
        labels: nhãn của từng người, dạng {người: {mã ca: nhãn}}.
        ids: mã các ca mà mọi người đều đã gán.
    """
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
    """Đọc nhãn, in báo cáo đồng thuận và, với --write-gold, ghi gold.jsonl cùng danh sách ca chưa có đa số."""
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
