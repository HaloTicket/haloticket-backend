"""Kiểm tra bằng người các nhãn do mô hình phân xử: python -m research.risk.review_sample [--n 20] [--seed 7] [--annotator H]

Lấy một mẫu theo hạt giống trong các ca mà mô hình phân xử đã quyết định, hiện từng ca KHÔNG kèm nhãn nào, hỏi nhãn của chính
bạn (cùng cú pháp với label.py), rồi mới cho xem nhãn chuẩn và lý do của người phân xử. Câu trả lời ghi vào
data/review_adjudication.jsonl; phần tóm tắt cuối cho biết một người đọc ca một mình đạt đúng mức chuẩn với tần suất nào.
Chạy lại để tiếp tục; cùng hạt giống thì mẫu giống nhau.
"""
import argparse
import os
import random

from research.risk.common import DATA, append_jsonl, load_cases, now_iso, read_jsonl
from research.risk.label import ask_label, latest
from research.risk.view import render

GOLD = os.path.join(DATA, "gold.jsonl")
OUT = os.path.join(DATA, "review_adjudication.jsonl")


def sample(seed: int, n: int) -> list:
    """Lấy mẫu `n` mã ca theo hạt giống trong các ca có nhãn chuẩn do người phân xử quyết định (method là adjudicated)."""
    adjudicated = sorted(r["id"] for r in read_jsonl(GOLD) if r["method"] == "adjudicated")
    random.Random(seed).shuffle(adjudicated)
    return adjudicated[:n]


def summary(rows: list) -> str:
    """Tóm tắt các câu trả lời của người xem lại: số ca, số ca trùng mức nhãn chuẩn, số ca trùng cả mức lẫn mã."""
    rows = list(latest(rows).values())
    if not rows:
        return "chưa có câu trả lời nào"
    level = sum(1 for r in rows if r["your_level"] == r["gold_level"])
    full = sum(1 for r in rows if r["your_level"] == r["gold_level"] and sorted(r["your_codes"]) == sorted(r["gold_codes"]))
    return f"{len(rows)} ca; trùng mức nhãn chuẩn {level}/{len(rows)}; trùng cả mức và mã {full}/{len(rows)}"


def run(n: int, seed: int, annotator: str, input_fn=input, output=print) -> None:
    """Vòng kiểm tra tương tác: hiện từng ca trong mẫu chưa xem lại, đọc nhãn của người xem, hiện nhãn chuẩn kèm lý do phân xử và lưu câu trả lời.

    Args:
        n: cỡ mẫu.
        seed: hạt giống chọn mẫu.
        annotator: mã người xem lại.
        input_fn: hàm đọc dòng nhập (thay được khi kiểm thử).
        output: hàm in (thay được khi kiểm thử).
    """
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
    """Điểm vào dòng lệnh: đọc --n, --seed, --annotator rồi chạy vòng kiểm tra."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--n", type=int, default=20)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--annotator", default="H")
    args = parser.parse_args()
    run(args.n, args.seed, args.annotator)


if __name__ == "__main__":
    main()
