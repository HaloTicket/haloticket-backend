"""Writes the case file annotators receive: python -m research.risk.make_blind (RISK_SET=t2)
Same cases and fixtures as cases.jsonl, minus everything that hints at the answer: the case group, the generator's intended
label, the disguise style, the model flag and the source."""
import os
import sys

from research.risk.common import CASES_PATH, DATA, read_jsonl, write_jsonl

HIDDEN = ("group", "intended", "needs_model", "disguise", "source")


def blind(rows: list) -> list:
    header, cases = dict(rows[0]), rows[1:]
    header.pop("seed", None)
    return [header] + [{k: v for k, v in case.items() if k not in HIDDEN} for case in cases]


if __name__ == "__main__":
    source = os.path.join(DATA, "cases.jsonl")
    target = os.path.join(DATA, "cases_blind.jsonl")
    rows = read_jsonl(source)
    write_jsonl(target, blind(rows))
    print(f"wrote {len(rows) - 1} cases to {target}")
