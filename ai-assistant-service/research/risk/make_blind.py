"""Ghi tệp ca gửi cho người gán nhãn: python -m research.risk.make_blind (RISK_SET=t2)
Cùng ca và fixtures như cases.jsonl, nhưng bỏ mọi thứ gợi ý đáp án: nhóm ca, nhãn dự định của bộ sinh, kiểu ngụy trang, cờ của mô hình và nguồn.
"""
import os
import sys

from research.risk.common import CASES_PATH, DATA, read_jsonl, write_jsonl

HIDDEN = ("group", "intended", "needs_model", "disguise", "source")


def blind(rows: list) -> list:
    """Tạo bản ẩn nhãn: bỏ khóa seed ở tiêu đề và các trường lộ đáp án (group, intended, needs_model, disguise, source) ở từng ca."""
    header, cases = dict(rows[0]), rows[1:]
    header.pop("seed", None)
    return [header] + [{k: v for k, v in case.items() if k not in HIDDEN} for case in cases]


if __name__ == "__main__":
    source = os.path.join(DATA, "cases.jsonl")
    target = os.path.join(DATA, "cases_blind.jsonl")
    rows = read_jsonl(source)
    write_jsonl(target, blind(rows))
    print(f"wrote {len(rows) - 1} cases to {target}")
