"""Gán nhãn theo lô, không dùng công cụ tương tác (dùng cho lượt gán của chính mô hình).

python -m research.risk.batch_label show  --annotator A --start 31 --end 60
python -m research.risk.batch_label put   --annotator A --start 31 --end 60 --out labels/_A_part31.jsonl   (đọc stdin)
python -m research.risk.batch_label status --annotator A --start 31 --end 60 --out labels/_A_part31.jsonl
python -m research.risk.batch_label merge --annotator A --parts labels/_A_part31.jsonl,labels/_A_part61.jsonl

Vị trí tính từ 1 trong thứ tự xáo trộn riêng của người gán nhãn (cùng thứ tự mà label.py duyệt). `show` in đúng
màn hình của người gán nhãn (không có nhóm, không có nhãn dự kiến, không có điểm). `put` đọc các dòng "position|entry|note", ví dụ
"37|2 r|short reason" trong đó entry có cú pháp như trong label.py; ghi chú chỉ bắt buộc khi có u.
"""
import argparse
import os
import sys

from research.risk.common import LABELS, append_jsonl, load_cases, now_iso, read_jsonl
from research.risk.label import PILOT_SIZE, latest, ordered_ids, parse_entry, path_for
from research.risk.view import render

BY = "claude-sonnet-5.5 (LLM), blind: saw only the annotator screen"


def window(annotator: str, start: int, end: int) -> tuple:
    """Kiểm tra khoảng vị trí start..end hợp lệ với thứ tự của người gán nhãn; trả ({id: ca}, thứ tự id). Thoát với lỗi nếu ngoài khoảng."""
    _, cases = load_cases()
    order = ordered_ids(cases, annotator)
    if not 1 <= start <= end <= len(order):
        raise SystemExit(f"positions must satisfy 1 <= start <= end <= {len(order)}")
    return {c["id"]: c for c in cases}, order


def show(annotator: str, start: int, end: int, output=print) -> None:
    """In màn hình gán nhãn của các ca ở vị trí start..end theo thứ tự của người gán nhãn."""
    by_id, order = window(annotator, start, end)
    for position in range(start, end + 1):
        cid = order[position - 1]
        output("\n" + "=" * 70 + f"\n#{position}  {cid}\n" + "-" * 70)
        output(render(by_id[cid]))


def parse_line(line: str, start: int, end: int):
    """Phân tích một dòng "position|entry|note", trả (position, entry, note) hoặc ném ValueError kèm lý do."""
    parts = [p.strip() for p in line.split("|", 2)]
    if len(parts) < 2 or not parts[0].isdigit():
        raise ValueError("expected position|entry|note")
    position = int(parts[0])
    if not start <= position <= end:
        raise ValueError(f"position {position} outside {start}-{end}")
    entry = parse_entry(parts[1])
    if entry is None:
        raise ValueError(f"cannot read the entry '{parts[1]}'")
    if (entry["level"] == "LOW") == bool(entry["codes"]):
        raise ValueError("LOW takes no code; MEDIUM and HIGH need at least one")
    note = parts[2] if len(parts) > 2 else ""
    if entry["unsure"] and not note:
        raise ValueError("an unsure label needs a note")
    return position, entry, note


def put(annotator: str, start: int, end: int, out: str, lines, output=print) -> int:
    """Đọc các dòng "position|entry|note" và ghi nhãn vào tệp `out`; dòng sai được báo lỗi và bỏ qua. Trả số dòng lỗi."""
    _, order = window(annotator, start, end)
    written, errors = 0, 0
    for raw in lines:
        if not raw.strip():
            continue
        try:
            position, entry, note = parse_line(raw.strip(), start, end)
        except ValueError as failure:
            errors += 1
            output(f"ERROR '{raw.strip()[:60]}': {failure}")
            continue
        append_jsonl(out, {"id": order[position - 1], "annotator": annotator, "by": BY, "level": entry["level"], "codes": entry["codes"],
                           "unsure": entry["unsure"], "note": note, "labelled_at": now_iso(), "order": position,
                           "pilot": position <= PILOT_SIZE})
        written += 1
    output(f"wrote {written}, errors {errors}")
    return errors


def status(annotator: str, start: int, end: int, out: str, output=print) -> list:
    """In số vị trí đã gán trong khoảng start..end và trả danh sách vị trí còn thiếu."""
    _, order = window(annotator, start, end)
    done = {r["order"] for r in read_jsonl(out)}
    missing = [p for p in range(start, end + 1) if p not in done]
    output(f"{len(done)}/{end - start + 1} positions labelled; missing: {missing if missing else 'none'}")
    return missing


def merge(annotator: str, parts: list, output=print) -> int:
    """Ghi nối các tệp phần vào labels/<annotator>.jsonl theo thứ tự vị trí. Từ chối ca trùng hoặc id sai vị trí. Trả tổng số nhãn."""
    _, cases = load_cases()
    order = ordered_ids(cases, annotator)
    existing = latest(read_jsonl(path_for(annotator)))
    rows = {}
    for part in parts:
        for row in read_jsonl(part):
            if row["id"] != order[row["order"] - 1]:
                raise SystemExit(f"{part}: id {row['id']} is not at position {row['order']} of the {annotator} order")
            rows[row["order"]] = row  # làm lại trong cùng một phần thì thay dòng trước đó
    clash = [r["id"] for r in rows.values() if r["id"] in existing]
    if clash:
        raise SystemExit(f"already labelled in {path_for(annotator)}: {clash[:5]}")
    for position in sorted(rows):
        append_jsonl(path_for(annotator), rows[position])
    total = len(latest(read_jsonl(path_for(annotator))))
    output(f"merged {len(rows)} labels; {annotator} now has {total}/{len(order)}")
    return total


def main() -> None:
    """Điểm vào dòng lệnh: chạy lệnh con show, put, status hoặc merge."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=("show", "put", "status", "merge"))
    parser.add_argument("--annotator", required=True)
    parser.add_argument("--start", type=int)
    parser.add_argument("--end", type=int)
    parser.add_argument("--out")
    parser.add_argument("--parts")
    args = parser.parse_args()
    if args.command == "merge":
        merge(args.annotator, [p for p in args.parts.split(",") if p])
    elif args.command == "show":
        show(args.annotator, args.start, args.end)
    elif args.command == "put":
        sys.exit(1 if put(args.annotator, args.start, args.end, args.out, sys.stdin.read().splitlines()) else 0)
    else:
        status(args.annotator, args.start, args.end, args.out)


if __name__ == "__main__":
    main()
