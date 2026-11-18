"""Phân xử theo lô cho người đọc không được biết nhãn nào của ai.

python -m research.risk.batch_adjudicate list                       có bao nhiêu ca cần quyết định
python -m research.risk.batch_adjudicate show --start 1 --end 20    các ca, kèm hai nhãn trước đó được xáo trộn thành "Lượt 1" và "Lượt 2"
python -m research.risk.batch_adjudicate put  --start 1 --end 20 --out research/risk/labels/_C_part1.jsonl   (đọc stdin)
python -m research.risk.batch_adjudicate status --start 1 --end 20 --out research/risk/labels/_C_part1.jsonl
python -m research.risk.batch_adjudicate merge --parts a.jsonl,b.jsonl                      ghi nối vào labels/C.jsonl

Vị trí n là ca thứ n (xếp theo id) trong các ca cần phân xử. Người gán nhãn nào là "Lượt 1" được quyết định theo từng
ca bằng mã băm của id, và không bao giờ được in ra. Các dòng của `put`: "n|entry|reason" (bắt buộc có lý do: một câu
nêu luật trong hướng dẫn hoặc những từ trong văn bản quyết định kết quả).
"""
import argparse
import hashlib
import sys

from research.risk.adjudicate import needs_adjudication
from research.risk.batch_label import parse_line
from research.risk.common import LABELS, append_jsonl, load_cases, now_iso, read_jsonl
from research.risk.label import latest, load_labels
from research.risk.view import render

BY = "adjudicator C: a model reader with a fresh context, shown the two earlier labels in shuffled order without their source"
FIRST, SECOND = "A", "B"


def cases_to_decide() -> list:
    """Các ca mà hai người gán nhãn chưa thống nhất và người phân xử chưa quyết định."""
    a, b, done = load_labels(FIRST), load_labels(SECOND), load_labels("C")
    return sorted(cid for cid in a if cid in b and needs_adjudication(a[cid], b[cid]) and cid not in done)


def shuffled_pair(cid: str) -> tuple:
    """(nhãn của "Lượt 1", nhãn của "Lượt 2"); việc gán chỉ phụ thuộc vào id."""
    a, b = load_labels(FIRST)[cid], load_labels(SECOND)[cid]
    return (a, b) if int(hashlib.sha256(cid.encode()).hexdigest(), 16) % 2 == 0 else (b, a)


def describe(label: dict) -> str:
    """Mô tả một nhãn thành một dòng: mức, mã, kèm cảnh báo chưa chắc và ghi chú nếu có."""
    text = f"{label['level']} {','.join(label['codes']) or '-'}"
    if label.get("unsure"):
        text += "  (người gán chưa chắc)"
    if label.get("note"):
        text += f"  ghi chú: {label['note']}"
    return text


def window(start: int, end: int) -> list:
    """Danh sách ca cần quyết định; thoát với lỗi nếu khoảng vị trí start..end nằm ngoài danh sách."""
    todo = cases_to_decide()
    if not 1 <= start <= end <= len(todo):
        raise SystemExit(f"positions must satisfy 1 <= start <= end <= {len(todo)}")
    return todo


def show(start: int, end: int, output=print) -> None:
    """In các ca ở vị trí start..end cùng hai nhãn trước đó (đã xáo trộn thành Lượt 1 và Lượt 2)."""
    todo = window(start, end)
    _, cases = load_cases()
    by_id = {c["id"]: c for c in cases}
    for n in range(start, end + 1):
        cid = todo[n - 1]
        first, second = shuffled_pair(cid)
        output("\n" + "=" * 70 + f"\n#{n}\n" + "-" * 70)
        output(render(by_id[cid], full_similar=True))
        output("-" * 70 + f"\nLượt 1: {describe(first)}\nLượt 2: {describe(second)}")


def put(start: int, end: int, out: str, lines, output=print) -> int:
    """Đọc các dòng "n|entry|reason" và ghi nhãn phân xử vào tệp `out`; dòng sai được báo lỗi và bỏ qua. Trả số dòng lỗi."""
    todo = window(start, end)
    written = errors = 0
    for raw in lines:
        if not raw.strip():
            continue
        try:
            position, entry, note = parse_line(raw.strip(), start, end)
            if not note or entry["unsure"]:
                raise ValueError("an adjudicated label needs a reason and cannot be unsure")
        except ValueError as failure:
            errors += 1
            output(f"ERROR '{raw.strip()[:60]}': {failure}")
            continue
        append_jsonl(out, {"id": todo[position - 1], "annotator": "C", "by": BY, "level": entry["level"], "codes": entry["codes"],
                           "unsure": False, "note": note, "labelled_at": now_iso(), "method": "adjudicated", "position": position})
        written += 1
    output(f"wrote {written}, errors {errors}")
    return errors


def status(start: int, end: int, out: str, output=print) -> list:
    """In số vị trí đã quyết trong khoảng start..end và trả danh sách vị trí còn thiếu."""
    window(start, end)
    done = {r["position"] for r in read_jsonl(out)}
    missing = [n for n in range(start, end + 1) if n not in done]
    output(f"{len(done)}/{end - start + 1} positions decided; missing: {missing if missing else 'none'}")
    return missing


def merge(parts: list, output=print) -> int:
    """Gộp các tệp phần vào labels/C.jsonl (kiểm tra vị trí khớp id, từ chối ca đã có). Trả số ca còn chưa quyết."""
    todo = cases_to_decide()
    path = f"{LABELS}/C.jsonl"
    existing = latest(read_jsonl(path))
    rows = {}
    for part in parts:
        for row in read_jsonl(part):
            if todo[row["position"] - 1] != row["id"]:
                raise SystemExit(f"{part}: position {row['position']} is not {row['id']}")
            rows[row["id"]] = row
    clash = [i for i in rows if i in existing]
    if clash:
        raise SystemExit(f"already in C.jsonl: {clash[:5]}")
    for cid in sorted(rows):
        append_jsonl(path, rows[cid])
    left = [c for c in todo if c not in latest(read_jsonl(path))]
    output(f"merged {len(rows)}; undecided {len(left)} of {len(todo)}")
    return len(left)


def main() -> None:
    """Điểm vào dòng lệnh: chạy lệnh con list, show, put, status hoặc merge."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=("list", "show", "put", "status", "merge"))
    parser.add_argument("--start", type=int)
    parser.add_argument("--end", type=int)
    parser.add_argument("--out")
    parser.add_argument("--parts")
    args = parser.parse_args()
    if args.command == "list":
        print(len(cases_to_decide()), "cases need a decision")
    elif args.command == "show":
        show(args.start, args.end)
    elif args.command == "put":
        sys.exit(1 if put(args.start, args.end, args.out, sys.stdin.read().splitlines()) else 0)
    elif args.command == "status":
        status(args.start, args.end, args.out)
    else:
        merge([p for p in (args.parts or "").split(",") if p])


if __name__ == "__main__":
    main()
