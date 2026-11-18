"""Công cụ gán nhãn mù: python -m research.risk.label --annotator A

Hiện từng ca một (không có điểm, không có nhóm, không có nhãn dự định), theo thứ tự xáo trộn riêng cho từng người gán, và
lưu mọi quyết định ngay vào labels/<annotator>.jsonl. Chạy lại để tiếp tục. Xem LABELING_GUIDE.md.
"""
import argparse
import hashlib
import os
import random
import re

from research.risk.common import CODE_KEYS, CODES, LABELS, LEVELS, append_jsonl, load_cases, now_iso, read_jsonl
from research.risk.view import render

PILOT_SIZE = 30
LEVEL_KEYS = {"1": "LOW", "2": "MEDIUM", "3": "HIGH"}


def parse_entry(text: str):
    """Phân tích một dòng nhập, ví dụ '2 o,s' cho {"level": "MEDIUM", "codes": [...], "unsure": False}. Trả về None khi dòng không phải quyết định.
    Các token: 1/2/3 là mức; p o s v r x d t (hoặc tên mã đầy đủ) là mã; u là chưa chắc. Bắt buộc có đúng một mức.
    """
    tokens = [t for t in re.split(r"[\s,;]+", text.strip()) if t]
    level, codes, unsure = None, [], False
    for token in tokens:
        low = token.lower()
        if low in LEVEL_KEYS:
            if level is not None:
                return None
            level = LEVEL_KEYS[low]
        elif low == "u":
            unsure = True
        elif token.upper() in CODES:
            codes.append(token.upper())
        elif all(ch in CODE_KEYS for ch in low):
            codes.extend(CODE_KEYS[ch] for ch in low)
        else:
            return None
    if level is None:
        return None
    return {"level": level, "codes": [c for c in CODES if c in set(codes)], "unsure": unsure}


def _seed(annotator: str, salt: str) -> int:
    """Hạt giống xáo trộn riêng của một người gán nhãn, suy ra từ băm SHA-256 của muối và tên."""
    return int(hashlib.sha256(f"{salt}:{annotator}".encode("utf-8")).hexdigest()[:12], 16)


def ordered_ids(cases: list, annotator: str) -> list:
    """Thứ tự gán của một người: PILOT_SIZE ca đầu là cùng một tập cho mọi người (để so sánh giai đoạn thử và chỉnh hướng dẫn);
    bên trong giai đoạn thử và sau đó, thứ tự được xáo trộn với hạt giống là mã người gán.
    """
    ids = sorted(c["id"] for c in cases)
    random.Random(0).shuffle(ids)
    pilot, rest = ids[:PILOT_SIZE], ids[PILOT_SIZE:]
    rng = random.Random(_seed(annotator, "order"))
    rng.shuffle(pilot)
    rng.shuffle(rest)
    return pilot + rest


def path_for(annotator: str) -> str:
    """Đường dẫn tệp nhãn của một người gán."""
    return os.path.join(LABELS, f"{annotator}.jsonl")


def latest(rows: list) -> dict:
    """Giữ dòng cuối cùng của mỗi mã ca (gán lại thì thêm dòng mới)."""
    out = {}
    for row in rows:
        out[row["id"]] = row
    return out


def load_labels(annotator: str) -> dict:
    """Đọc nhãn đã lưu của một người gán, mỗi ca một dòng mới nhất."""
    return latest(read_jsonl(path_for(annotator)))


def ask_label(input_fn, output, allow_back: bool = True):
    """Đọc một quyết định, hỏi lại tới khi hợp lệ: mức LOW không có mã, mức MEDIUM hoặc HIGH cần ít nhất một mã, chưa chắc thì hỏi thêm ghi chú.

    Returns:
        "q" (thoát), "b" (quay lại, chỉ khi allow_back) hoặc {"level", "codes", "unsure", "note"}.
    """
    while True:
        raw = input_fn("Nhãn (vd '2 o,s'; " + ("b=quay lại, " if allow_back else "") + "q=thoát; thêm u nếu chưa chắc): ").strip()
        if raw.lower() == "q":
            return "q"
        if raw.lower() == "b" and allow_back:
            return "b"
        entry = parse_entry(raw)
        if entry is None:
            output("Không hiểu. Cần một mức (1, 2 hoặc 3), các mã nếu có (p,o,s,v,r,x,d,t) và u nếu chưa chắc.")
            continue
        if entry["level"] == "LOW" and entry["codes"]:
            output("Mức LOW không có mã vi phạm. Nhập lại (hoặc chọn mức khác).")
            continue
        if entry["level"] != "LOW" and not entry["codes"]:
            output("Mức MEDIUM hoặc HIGH cần ít nhất một mã. Nhập lại.")
            continue
        entry["note"] = input_fn("Ghi chú vì sao chưa chắc (một câu): ").strip() if entry["unsure"] else ""
        return entry


def save_label(annotator: str, cid: str, entry: dict, order: int, pilot: bool) -> None:
    """Ghi một nhãn vào cuối tệp nhãn của người gán, kèm thời điểm, thứ tự và cờ giai đoạn thử."""
    append_jsonl(path_for(annotator), {"id": cid, "annotator": annotator, "level": entry["level"], "codes": entry["codes"],
                                       "unsure": entry["unsure"], "note": entry["note"], "labelled_at": now_iso(), "order": order,
                                       "pilot": pilot})


def run(annotator: str, cases_path=None, input_fn=input, output=print, redo=None) -> None:
    """Vòng gán nhãn tương tác cho một người.

    Args:
        annotator: mã người gán.
        cases_path: tệp ca; None thì dùng tệp mặc định.
        input_fn: hàm đọc dòng nhập (thay được khi kiểm thử).
        output: hàm in (thay được khi kiểm thử).
        redo: danh sách mã ca cần gán lại (nhãn mới thay nhãn cũ); None thì chạy lượt gán bình thường.

    Raises:
        SystemExit: khi redo có mã chưa được người này gán.
    """
    _, cases = load_cases(cases_path) if cases_path else load_cases()
    by_id = {c["id"]: c for c in cases}
    order = ordered_ids(cases, annotator)
    pilot = set(order[:PILOT_SIZE])

    if redo is not None:
        done = load_labels(annotator)
        unknown = [cid for cid in redo if cid not in by_id or cid not in done]
        if unknown:
            raise SystemExit(f"These ids are not labelled yet by {annotator}: {', '.join(unknown)}")
        for n, cid in enumerate(redo, 1):
            output("\n" + "=" * 78 + f"\nGán lại {n}/{len(redo)}: {cid}\n" + "-" * 78)
            output(render(by_id[cid]))
            old = done[cid]
            output("-" * 78 + f"\n(nhãn cũ: {old['level']} {','.join(old['codes']) or '-'})")
            entry = ask_label(input_fn, output, allow_back=False)
            if entry == "q":
                output("Đã lưu. Các ca chưa gán lại giữ nhãn cũ.")
                return
            save_label(annotator, cid, entry, order.index(cid) + 1, cid in pilot)
            output(f"  -> {entry['level']} {','.join(entry['codes']) or '-'}{'  (chưa chắc)' if entry['unsure'] else ''}")
        output("Đã gán lại xong.")
        return

    def first_unlabelled(start: int, done: dict) -> int:
        """Vị trí đầu tiên từ start trở đi mà ca chưa có nhãn; bằng độ dài danh sách nếu đã gán hết."""
        return next((i for i in range(start, len(order)) if order[i] not in done), len(order))

    position = first_unlabelled(0, load_labels(annotator))
    while True:
        done = load_labels(annotator)
        if position >= len(order):
            position = first_unlabelled(0, done)
        if position >= len(order):
            output(f"Đã gán xong {len(done)}/{len(order)} ca.")
            return
        cid = order[position]
        output("\n" + "=" * 78)
        output(f"Ca {position + 1}/{len(order)}  (đã gán {len(done)})" + ("  [giai đoạn thử]" if cid in pilot else "")
               + ("  [gán lại]" if cid in done else ""))
        output("-" * 78)
        output(render(by_id[cid]))
        output("-" * 78)
        entry = ask_label(input_fn, output)
        if entry == "q":
            output("Đã lưu. Chạy lại cùng lệnh để tiếp tục.")
            return
        if entry == "b":
            position = max(0, position - 1)
            continue
        save_label(annotator, cid, entry, position + 1, cid in pilot)
        output(f"  -> {entry['level']} {','.join(entry['codes']) or '-'}{'  (chưa chắc)' if entry['unsure'] else ''}")
        position = first_unlabelled(position + 1, load_labels(annotator))


def main() -> None:
    """Điểm vào dòng lệnh: kiểm tra mã người gán rồi chạy lượt gán (hoặc gán lại theo --redo)."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--annotator", required=True, help="a short id such as A or B; it also seeds the shuffled order")
    parser.add_argument("--redo", help="comma list of case ids to label again (the new label replaces the old one)")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,16}", args.annotator):
        raise SystemExit("annotator id: letters, digits, - or _ only (max 16)")
    run(args.annotator, redo=[i.strip() for i in args.redo.split(',') if i.strip()] if args.redo else None)


if __name__ == "__main__":
    main()
