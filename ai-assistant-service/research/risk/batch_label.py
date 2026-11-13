"""Labelling in batches without the interactive tool (used for the model's own pass).

python -m research.risk.batch_label show  --annotator A --start 31 --end 60
python -m research.risk.batch_label put   --annotator A --start 31 --end 60 --out labels/_A_part31.jsonl   (reads stdin)
python -m research.risk.batch_label status --annotator A --start 31 --end 60 --out labels/_A_part31.jsonl
python -m research.risk.batch_label merge --annotator A --parts labels/_A_part31.jsonl,labels/_A_part61.jsonl

Positions are 1-based places in the annotator's own shuffled order (the same order label.py walks). `show` prints exactly
the annotator screen (no group, no intended label, no score). `put` reads lines "position|entry|note", for example
"37|2 r|short reason" where entry has the same syntax as in label.py; the note is required only with u.
"""
import argparse
import os
import sys

from research.risk.common import LABELS, append_jsonl, load_cases, now_iso, read_jsonl
from research.risk.label import PILOT_SIZE, latest, ordered_ids, parse_entry, path_for
from research.risk.view import render

BY = "claude-sonnet-5.5 (LLM), blind: saw only the annotator screen"


def window(annotator: str, start: int, end: int) -> tuple:
    _, cases = load_cases()
    order = ordered_ids(cases, annotator)
    if not 1 <= start <= end <= len(order):
        raise SystemExit(f"positions must satisfy 1 <= start <= end <= {len(order)}")
    return {c["id"]: c for c in cases}, order


def show(annotator: str, start: int, end: int, output=print) -> None:
    by_id, order = window(annotator, start, end)
    for position in range(start, end + 1):
        cid = order[position - 1]
        output("\n" + "=" * 70 + f"\n#{position}  {cid}\n" + "-" * 70)
        output(render(by_id[cid]))


def parse_line(line: str, start: int, end: int):
    """(position, entry, note) or raises ValueError with the reason."""
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
    _, order = window(annotator, start, end)
    done = {r["order"] for r in read_jsonl(out)}
    missing = [p for p in range(start, end + 1) if p not in done]
    output(f"{len(done)}/{end - start + 1} positions labelled; missing: {missing if missing else 'none'}")
    return missing


def merge(annotator: str, parts: list, output=print) -> int:
    """Appends the parts to labels/<annotator>.jsonl in position order. Refuses duplicates or a gap against the pilot."""
    _, cases = load_cases()
    order = ordered_ids(cases, annotator)
    existing = latest(read_jsonl(path_for(annotator)))
    rows = {}
    for part in parts:
        for row in read_jsonl(part):
            if row["id"] != order[row["order"] - 1]:
                raise SystemExit(f"{part}: id {row['id']} is not at position {row['order']} of the {annotator} order")
            rows[row["order"]] = row  # a redo inside a part replaces the earlier line
    clash = [r["id"] for r in rows.values() if r["id"] in existing]
    if clash:
        raise SystemExit(f"already labelled in {path_for(annotator)}: {clash[:5]}")
    for position in sorted(rows):
        append_jsonl(path_for(annotator), rows[position])
    total = len(latest(read_jsonl(path_for(annotator))))
    output(f"merged {len(rows)} labels; {annotator} now has {total}/{len(order)}")
    return total


def main() -> None:
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
