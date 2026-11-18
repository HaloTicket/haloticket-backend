"""Phân xử bất đồng giữa các người gán nhãn và tạo tệp nhãn vàng (gold).

Cách dùng (từ ai-assistant-service/):
    # 1. Tính độ đồng thuận và liệt kê các bất đồng
    python -m research.risk.adjudicate --report

    # 2. Phân xử tương tác (các ca mà A và B khác nhau về mức hoặc mã, hoặc một người đánh dấu chưa chắc)
    python -m research.risk.adjudicate --annotators A,B --adjudicator C --interactive

    # 3. Tạo tệp gold từ nhãn đã đồng thuận và đã phân xử (khi mọi bất đồng đã được giải quyết)
    python -m research.risk.adjudicate --annotators A,B --adjudicator C --build-gold

Đầu ra
------
research/risk/data/gold.jsonl     – mỗi ca một dòng: {id, level, codes, method, note}
research/risk/data/agreement.json – điểm kappa và danh sách bất đồng
research/risk/data/split.json     – {"dev": [...ids], "test": [...ids]} chia 30/70, phân tầng theo nhóm ca, chỉ ghi một lần
                                    (bị từ chối khi đã tồn tại: tập test bị khóa; dùng --force-split để cố ý dựng lại)
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sys
from pathlib import Path

HERE = Path(__file__).parent
AI_SERVICE = HERE.parent.parent
sys.path.insert(0, str(AI_SERVICE))

from research.risk.common import (
    CODES, DATA, GOLD_PATH, LABELS, LEVELS, SPLIT_PATH,
    append_jsonl, load_cases, now_iso, read_jsonl, write_jsonl,
)
from research.risk.label import parse_entry
from research.risk.stats import cohen_kappa, kappa_band
from research.risk.view import render


# ---------------------------------------------------------------------------
# Nạp nhãn
# ---------------------------------------------------------------------------

def _latest_labels(path: str) -> dict:
    """id -> dòng cuối cùng của id đó (làm lại sẽ thêm một dòng mới)."""
    rows = read_jsonl(path)
    out = {}
    for r in rows:
        out[r["id"]] = r
    return out


def load_annotator_labels(annotators: list[str]) -> dict[str, dict]:
    """annotator_id -> {case_id -> dòng nhãn}. Người chưa có tệp nhãn được cảnh báo và coi như chưa gán ca nào."""
    result = {}
    for ann in annotators:
        path = os.path.join(LABELS, f"{ann}.jsonl")
        if not os.path.exists(path):
            print(f"[warn] Labels for {ann} not found: {path}")
            result[ann] = {}
        else:
            result[ann] = _latest_labels(path)
    return result


# ---------------------------------------------------------------------------
# Tính độ đồng thuận
# ---------------------------------------------------------------------------

def compute_agreement(ann_labels: dict[str, dict]) -> dict:
    """Trả thống kê đồng thuận cho mọi cặp người gán nhãn trên các ca mà tất cả đều đã gán (kappa theo mức, kappa theo từng mã, danh sách bất đồng)."""
    ann_ids = list(ann_labels.keys())
    if len(ann_ids) < 2:
        return {"error": "need at least 2 annotators"}

    # Tìm các ca mà mọi người đều đã gán nhãn
    common_ids = set.intersection(*[set(labels.keys()) for labels in ann_labels.values()])
    common_ids = sorted(common_ids)
    n = len(common_ids)

    pairs = []
    for i in range(len(ann_ids)):
        for j in range(i + 1, len(ann_ids)):
            a, b = ann_ids[i], ann_ids[j]
            levels_a = [ann_labels[a][cid]["level"] for cid in common_ids]
            levels_b = [ann_labels[b][cid]["level"] for cid in common_ids]

            kappa = cohen_kappa(levels_a, levels_b, LEVELS)
            kappa_lin = cohen_kappa(levels_a, levels_b, LEVELS, weights="linear")

            # Kappa theo từng mã (nhị phân: có mã hay không)
            code_kappas = {}
            for code in CODES:
                a_has = [code in ann_labels[a][cid].get("codes", []) for cid in common_ids]
                b_has = [code in ann_labels[b][cid].get("codes", []) for cid in common_ids]
                try:
                    code_kappas[code] = round(cohen_kappa(
                        ["yes" if x else "no" for x in a_has],
                        ["yes" if x else "no" for x in b_has],
                        ["yes", "no"],
                    ), 4)
                except ValueError:
                    code_kappas[code] = None

            # Các bất đồng
            disagreements = []
            for cid in common_ids:
                la, lb = ann_labels[a][cid], ann_labels[b][cid]
                if la["level"] != lb["level"] or set(la.get("codes", [])) != set(lb.get("codes", [])):
                    disagreements.append({
                        "id": cid,
                        a: {"level": la["level"], "codes": la.get("codes", [])},
                        b: {"level": lb["level"], "codes": lb.get("codes", [])},
                    })

            pairs.append({
                "annotators": [a, b],
                "n_common": n,
                "kappa_nominal": round(kappa, 4),
                "kappa_linear": round(kappa_lin, 4),
                "kappa_band": kappa_band(kappa),
                "n_disagree_level": sum(1 for cid in common_ids if ann_labels[a][cid]["level"] != ann_labels[b][cid]["level"]),
                "n_disagree_code": sum(
                    1 for cid in common_ids
                    if set(ann_labels[a][cid].get("codes", [])) != set(ann_labels[b][cid].get("codes", []))
                ),
                "code_kappas": code_kappas,
                "disagreements": disagreements,
            })

    return {"pairs": pairs, "annotators": ann_ids, "n_common": n}


# ---------------------------------------------------------------------------
# Tạo gold
# ---------------------------------------------------------------------------

def needs_adjudication(la: dict, lb: dict) -> bool:
    """Mức hoặc mã khác nhau, hoặc một trong hai người chưa chắc (ca chưa chắc luôn được đọc lại)."""
    return (la["level"] != lb["level"] or set(la.get("codes", [])) != set(lb.get("codes", []))
            or bool(la.get("unsure")) or bool(lb.get("unsure")))


def build_gold(ann_labels: dict[str, dict], adjudicator_path: str | None = None) -> list:
    """Tạo nhãn gold. Ca đồng thuận: method=agreed. Ca đã phân xử: method=adjudicated. Ca bất đồng mà người phân xử chưa quyết thì bị bỏ.
    Trả danh sách các dòng gold."""
    ann_ids = list(ann_labels.keys())
    if len(ann_ids) < 2:
        raise ValueError("Need at least 2 annotators to build gold.")

    # Nạp nhãn của người phân xử nếu có
    adj_labels = {}
    if adjudicator_path and os.path.exists(adjudicator_path):
        adj_labels = _latest_labels(adjudicator_path)

    # Tìm các ca chung
    common_ids = sorted(set.intersection(*[set(labels.keys()) for labels in ann_labels.values()]))

    gold = []
    a, b = ann_ids[0], ann_ids[1]
    for cid in common_ids:
        la = ann_labels[a][cid]
        lb = ann_labels[b][cid]
        if not needs_adjudication(la, lb):
            gold.append({
                "id": cid, "level": la["level"], "codes": la.get("codes", []),
                "method": "agreed", "note": "",
            })
        elif cid in adj_labels:
            adj = adj_labels[cid]
            gold.append({
                "id": cid, "level": adj["level"], "codes": adj.get("codes", []),
                "method": "adjudicated", "note": adj.get("note", ""),
            })
        else:
            # Chưa giải quyết: bỏ khỏi gold (sẽ được báo cáo)
            pass

    return gold


# ---------------------------------------------------------------------------
# Dựng phép chia dev/test
# ---------------------------------------------------------------------------

def build_split(gold_rows: list, groups: dict, dev_ratio: float = 0.30, seed: int = 42) -> dict:
    """Chia 30% dev, 70% test, phân tầng theo nhóm ca của bộ sinh dữ liệu (để nhóm nào cũng có ở cả hai tập). Cố định theo seed."""
    by_group = {}
    for r in gold_rows:
        by_group.setdefault(groups.get(r["id"], "unknown"), []).append(r["id"])
    dev_ids, test_ids = [], []
    rng = random.Random(seed)
    for name in sorted(by_group):
        ids = sorted(by_group[name])
        rng.shuffle(ids)
        n_dev = round(len(ids) * dev_ratio) if len(ids) >= 2 else 0
        dev_ids.extend(ids[:n_dev])
        test_ids.extend(ids[n_dev:])
    return {"seed": seed, "dev_ratio": dev_ratio, "dev": sorted(dev_ids), "test": sorted(test_ids)}


def adjudicate_interactively(ann_labels: dict, adjudicator: str, input_fn=input, output=print) -> int:
    """Hiển thị từng ca cần quyết định cùng hai nhãn; quyết định và lý do một câu được ghi vào
    labels/<adjudicator>.jsonl. Trả số ca đã quyết. Nhập q để dừng (tiến độ được giữ)."""
    _, cases = load_cases()
    by_id = {c["id"]: c for c in cases}
    a, b = list(ann_labels)[:2]
    path = os.path.join(LABELS, f"{adjudicator}.jsonl")
    decided = _latest_labels(path)
    todo = [cid for cid in sorted(set(ann_labels[a]) & set(ann_labels[b]))
            if needs_adjudication(ann_labels[a][cid], ann_labels[b][cid]) and cid not in decided]
    count = 0
    for n, cid in enumerate(todo, 1):
        output("\n" + "=" * 78 + f"\nCa {cid} ({n}/{len(todo)} cần phân xử)\n" + "-" * 78)
        output(render(by_id[cid]))
        for who in (a, b):
            lab = ann_labels[who][cid]
            output(f"{who}: {lab['level']} {','.join(lab.get('codes', [])) or '-'}{'  (chưa chắc)' if lab.get('unsure') else ''}"
                   + (f"  ghi chú: {lab['note']}" if lab.get("note") else ""))
        while True:
            raw = input_fn("Nhãn cuối (vd '2 o'; q=thoát): ").strip()
            if raw.lower() == "q":
                return count
            entry = parse_entry(raw)
            if entry is None or (entry["level"] == "LOW") == bool(entry["codes"]):
                output("Cần một mức; LOW không có mã, MEDIUM và HIGH cần ít nhất một mã.")
                continue
            reason = input_fn("Lý do (một câu): ").strip()
            if not reason:
                output("Cần ghi lý do.")
                continue
            append_jsonl(path, {"id": cid, "annotator": adjudicator, "level": entry["level"], "codes": entry["codes"], "unsure": False,
                                "note": reason, "labelled_at": now_iso(), "method": "adjudicated"})
            count += 1
            break
    return count


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    """Điểm vào dòng lệnh: in báo cáo đồng thuận, nhắc bước phân xử, hoặc tạo gold.jsonl và split.json tùy các cờ."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--annotators", default="A,B",
                        help="Comma-separated annotator ids (default: A,B)")
    parser.add_argument("--adjudicator", default="C",
                        help="Adjudicator id for disagreements (default: C)")
    parser.add_argument("--report", action="store_true",
                        help="Print agreement report and list disagreements")
    parser.add_argument("--build-gold", action="store_true",
                        help="Build gold.jsonl and split.json from current labels")
    parser.add_argument("--interactive", action="store_true",
                        help="Adjudicate the cases the two annotators did not settle (writes labels/<adjudicator>.jsonl)")
    parser.add_argument("--allow-partial", action="store_true", help="build the gold even though some cases are unresolved")
    parser.add_argument("--force-split", action="store_true", help="rebuild split.json (it is locked once written)")
    args = parser.parse_args()

    annotators = [a.strip() for a in args.annotators.split(",") if a.strip()]
    ann_labels = load_annotator_labels(annotators)

    if args.report or not (args.build_gold or args.interactive):
        agreement = compute_agreement(ann_labels)
        print(json.dumps(agreement, ensure_ascii=False, indent=2))
        # Lưu báo cáo đồng thuận
        report_path = os.path.join(DATA, "agreement.json")
        os.makedirs(DATA, exist_ok=True)
        with open(report_path, "w", encoding="utf-8") as f:
            json.dump(agreement, f, ensure_ascii=False, indent=2)
        print(f"\nSaved to {report_path}")

    if args.interactive:
        print("\n[interactive] Run python -m research.risk.label --annotator", args.adjudicator)
        print("to label the adjudicator's view, then run --build-gold.")

    if args.build_gold:
        adj_path = os.path.join(LABELS, f"{args.adjudicator}.jsonl")
        gold_rows = build_gold(ann_labels, adj_path)
        unresolved = [
            cid for cid in sorted(set.intersection(*[set(l.keys()) for l in ann_labels.values()]))
            if not any(r["id"] == cid for r in gold_rows)
        ]
        print(f"Gold cases built: {len(gold_rows)}")
        if unresolved:
            print(f"Unresolved (not in gold): {len(unresolved)} cases — adjudicator labels needed.")
            for cid in unresolved[:20]:
                print(f"  {cid}")

        if unresolved and not args.allow_partial:
            raise SystemExit("Gold not written: resolve the cases above first (or --allow-partial).")
        os.makedirs(DATA, exist_ok=True)
        write_jsonl(GOLD_PATH, gold_rows)
        print(f"Wrote {GOLD_PATH}")

        if os.path.exists(SPLIT_PATH) and not args.force_split:
            print(f"{SPLIT_PATH} exists and is locked; kept as it is (--force-split to rebuild).")
        else:
            _, cases = load_cases()
            split = build_split(gold_rows, {c["id"]: c["group"] for c in cases})
            with open(SPLIT_PATH, "w", encoding="utf-8") as f:
                json.dump(split, f, ensure_ascii=False, indent=2)
            print(f"Wrote {SPLIT_PATH}  (dev={len(split['dev'])}, test={len(split['test'])})")


if __name__ == "__main__":
    main()

