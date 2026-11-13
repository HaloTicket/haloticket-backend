"""Adjudication in batches for a reader who must not know whose label is whose.

python -m research.risk.batch_adjudicate list                       how many cases need a decision
python -m research.risk.batch_adjudicate show --start 1 --end 20    the cases, with the two earlier labels shuffled as "Lượt 1" and "Lượt 2"
python -m research.risk.batch_adjudicate put  --start 1 --end 20 --out research/risk/labels/_C_part1.jsonl   (reads stdin)
python -m research.risk.batch_adjudicate status --start 1 --end 20 --out research/risk/labels/_C_part1.jsonl
python -m research.risk.batch_adjudicate merge --parts a.jsonl,b.jsonl                      appends to labels/C.jsonl

Position n is the n-th case (sorted by id) among those that need adjudication. Which annotator is "Lượt 1" is decided per
case by a hash of the id, and is never printed. `put` lines: "n|entry|reason" (the reason is required: one sentence that
names the rule in the guide or the words of the text that decide it).
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
    """Cases the two annotators did not settle and that the adjudicator has not decided yet."""
    a, b, done = load_labels(FIRST), load_labels(SECOND), load_labels("C")
    return sorted(cid for cid in a if cid in b and needs_adjudication(a[cid], b[cid]) and cid not in done)


def shuffled_pair(cid: str) -> tuple:
    """(label of "Lượt 1", label of "Lượt 2"); the assignment depends only on the id."""
    a, b = load_labels(FIRST)[cid], load_labels(SECOND)[cid]
    return (a, b) if int(hashlib.sha256(cid.encode()).hexdigest(), 16) % 2 == 0 else (b, a)


def describe(label: dict) -> str:
    text = f"{label['level']} {','.join(label['codes']) or '-'}"
    if label.get("unsure"):
        text += "  (người gán chưa chắc)"
    if label.get("note"):
        text += f"  ghi chú: {label['note']}"
    return text


def window(start: int, end: int) -> list:
    todo = cases_to_decide()
    if not 1 <= start <= end <= len(todo):
        raise SystemExit(f"positions must satisfy 1 <= start <= end <= {len(todo)}")
    return todo


def show(start: int, end: int, output=print) -> None:
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
    window(start, end)
    done = {r["position"] for r in read_jsonl(out)}
    missing = [n for n in range(start, end + 1) if n not in done]
    output(f"{len(done)}/{end - start + 1} positions decided; missing: {missing if missing else 'none'}")
    return missing


def merge(parts: list, output=print) -> int:
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
