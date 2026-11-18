"""RQ2: một người đánh giá các cờ mà mô hình đề xuất. python -m research.risk.flag_review label --annotator H1

Hiện từng cờ một (ca, mã cờ và trích dẫn; không bao giờ cho biết hệ thống giữ hay bỏ cờ), theo thứ tự xáo trộn riêng cho từng
người gán, và lưu mọi quyết định ngay vào labels/<set>/flags_<annotator>.jsonl. Chạy lại để tiếp tục.
`python -m research.risk.flag_review summary --annotator H1` đối chiếu các quyết định với việc hệ thống đã làm.

Quyết định: d = đúng (đúng loại vi phạm), n = nhãn sai (có vấn đề nhưng thuộc loại khác), f = không phải vi phạm (báo sai),
u = chưa chắc (thêm sau chữ cái, vd 'f u'). Trích dẫn không có trong văn bản là trích dẫn bịa; việc này do chương trình
(engine.validate_ai_flags) quyết định chứ không phải người gán, và được báo cáo riêng.
"""
import argparse
import hashlib
import json
import math
import os
import random
import re

from app.services.risk import content, engine, semantic
from research.risk.common import CACHE, LABELS, append_jsonl, load_cases, now_iso, read_jsonl
from research.risk.view import render

VERDICTS = {"d": "CORRECT", "n": "WRONG_CODE", "f": "NOT_A_VIOLATION"}
DEFAULT_TAG = "t2-groq-oss20b"


def cache_path(tag: str) -> str:
    """Đường dẫn tệp cache phản hồi của mô hình theo thẻ (tag)."""
    return os.path.join(CACHE, f"{tag}.jsonl")


def load_cache(tag: str) -> dict:
    """Đọc cache phản hồi của mô hình thành từ điển {khóa: văn bản trả lời}."""
    out = {}
    for row in read_jsonl(cache_path(tag)):
        out[row["key"]] = row["text"]
    return out


def build_items(cases: list, cache: dict, repeat: int = 0) -> list:
    """Dựng mỗi cờ mô hình đề xuất trong lần lặp đã cho thành một mục, kèm việc hệ thống đã làm với nó:
    quote_ok (qua bước kiểm tra trích dẫn nguyên văn, C4) và kept_after_verify (qua cả lần đọc hai, C5 trước khi bỏ trùng).

    Args:
        cases: danh sách ca.
        cache: cache phản hồi của mô hình.
        repeat: số thứ tự lần lặp cần lấy.

    Returns:
        danh sách mục; ca không có phản hồi hoặc phản hồi lỗi bị bỏ qua, mỗi ca chỉ xét tối đa 10 cờ đầu.
    """
    items = []
    for case in cases:
        raw_text = cache.get(f"{case['id']}|flags|{repeat}")
        if raw_text is None:
            continue
        try:
            raw = semantic.parse_flags(raw_text)
        except ValueError:
            continue
        texts = content.build(case["event"])["texts"]
        quote_ok_codes = {f["code"]: f for f in engine.validate_ai_flags(raw, texts)}
        verify_text = cache.get(f"{case['id']}|verify|{repeat}")
        confirmed = None
        if verify_text is not None:
            try:
                confirmed = set(semantic.parse_confirmed(verify_text))
            except ValueError:
                confirmed = None
        for index, flag in enumerate(raw[:10]):
            if not isinstance(flag, dict):
                continue
            code, quote = flag.get("code"), flag.get("quote")
            kept = quote_ok_codes.get(code)
            quote_ok = bool(kept) and isinstance(quote, str) and kept["evidence"]["text"] == quote.strip()
            items.append({"item": f"{case['id']}#{index}", "case": case["id"], "code": code, "quote": quote,
                          "confidence": flag.get("confidence"), "quote_ok": quote_ok,
                          "kept_after_verify": bool(quote_ok and (confirmed is None or code in confirmed))})
    return items


def _seed(annotator: str) -> int:
    """Hạt giống xáo trộn riêng của một người gán nhãn, suy ra từ băm SHA-256 của tên."""
    return int(hashlib.sha256(f"flags:{annotator}".encode("utf-8")).hexdigest()[:12], 16)


AGREEMENT_SEED = "flags-agreement-sample"


def agreement_sample(items: list, size: int) -> list:
    """Chọn cùng `size` mục cho mọi người gán (hạt giống cố định, không phụ thuộc người hỏi) để so sánh được hai người.
    Một nửa lấy từ VEILED_PROHIBITED (mã mà người gán đầu tiên chia nhiều nhất) và một nửa từ các mã khác; việc chia chỉ dựa
    vào mã, không bao giờ dựa vào nhãn của ai. Kappa trên mẫu này không phải kappa của mọi cờ.
    """
    rng = random.Random(int(hashlib.sha256(AGREEMENT_SEED.encode("utf-8")).hexdigest()[:12], 16))
    veiled = sorted(i["item"] for i in items if i["quote_ok"] and i["code"] == "VEILED_PROHIBITED")
    other = sorted(i["item"] for i in items if i["quote_ok"] and i["code"] != "VEILED_PROHIBITED")
    rng.shuffle(veiled)
    rng.shuffle(other)
    half = size // 2
    return veiled[:half] + other[:size - half]


def ordered(items: list, annotator: str, sample: int = 0) -> list:
    """Trả về mã các mục cần người đánh giá (trích dẫn có thật), xáo trộn riêng theo người gán. Trích dẫn bịa bị bỏ qua.
    sample > 0: chỉ lấy mẫu đồng thuận dùng chung có kích thước đó.
    """
    ids = agreement_sample(items, sample) if sample else [i["item"] for i in items if i["quote_ok"]]
    random.Random(_seed(annotator)).shuffle(ids)
    return ids


def path_for(annotator: str) -> str:
    """Đường dẫn tệp nhãn cờ của một người gán."""
    return os.path.join(LABELS, f"flags_{annotator}.jsonl")


def latest(rows: list) -> dict:
    """Giữ dòng cuối cùng của mỗi mục (gán lại thì dòng mới thay dòng cũ)."""
    out = {}
    for row in rows:
        out[row["item"]] = row
    return out


def parse_decision(text: str):
    """Phân tích một dòng nhập thành quyết định: 'f' cho ("NOT_A_VIOLATION", False); 'n u' cho ("WRONG_CODE", True). Trả về None khi dòng không phải quyết định hợp lệ."""
    tokens = [t for t in re.split(r"[\s,;]+", text.strip().lower()) if t]
    verdict, unsure = None, False
    for token in tokens:
        if token in VERDICTS and verdict is None:
            verdict = VERDICTS[token]
        elif token == "u":
            unsure = True
        else:
            return None
    return (verdict, unsure) if verdict else None


def show(case: dict, item: dict, output) -> None:
    """In một cờ cho người gán: nội dung ca, mã cờ, độ tin cậy, trích dẫn và định nghĩa của mã (không in việc hệ thống giữ hay bỏ)."""
    output(render(case))
    output("-" * 78)
    output(f"CỜ MÔ HÌNH ĐỀ XUẤT:  mã = {item['code']}   độ tin cậy = {item['confidence']}")
    output(f"Trích dẫn: \"{item['quote']}\"")
    output(semantic.FLAG_DEFINITIONS.get(item["code"], "") and f"Định nghĩa của mã: {semantic.FLAG_DEFINITIONS[item['code']]}")


def run_label(annotator: str, tag: str = DEFAULT_TAG, input_fn=input, output=print, cases=None, cache=None, labels_path=None,
              sample: int = 0) -> None:
    """Vòng gán nhãn tương tác: hiện từng cờ chưa gán, đọc quyết định (d, n, f, thêm u; b quay lại; q thoát) và ghi ngay vào tệp nhãn.

    Args:
        annotator: mã người gán.
        tag: thẻ cache phản hồi.
        input_fn: hàm đọc dòng nhập (thay được khi kiểm thử).
        output: hàm in (thay được khi kiểm thử).
        cases: danh sách ca; None thì đọc từ tệp.
        cache: cache phản hồi; None thì đọc theo tag.
        labels_path: đường dẫn tệp nhãn; None thì dùng đường dẫn mặc định của người gán.
        sample: > 0 thì chỉ gán mẫu đồng thuận dùng chung có kích thước đó.
    """
    cases = cases if cases is not None else load_cases()[1]
    by_case = {c["id"]: c for c in cases}
    items = build_items(cases, cache if cache is not None else load_cache(tag))
    by_item = {i["item"]: i for i in items}
    order = ordered(items, annotator, sample)
    path = labels_path or path_for(annotator)
    position = 0
    while True:
        done = latest(read_jsonl(path))
        position = next((p for p in range(position, len(order)) if order[p] not in done), None)
        if position is None:
            position = next((p for p in range(len(order)) if order[p] not in done), None)
        if position is None:
            output(f"Đã gán xong {len(done)}/{len(order)} cờ.")
            return
        item = by_item[order[position]]
        output("\n" + "=" * 78)
        output(f"Cờ {position + 1}/{len(order)}  (đã gán {len(done)})")
        output("-" * 78)
        show(by_case[item["case"]], item, output)
        output("-" * 78)
        raw = input_fn("d=đúng loại, n=sai loại, f=không phải vi phạm (thêm u nếu chưa chắc; b=quay lại, q=thoát): ").strip()
        if raw.lower() == "q":
            output("Đã lưu. Chạy lại cùng lệnh để tiếp tục.")
            return
        if raw.lower() == "b":
            position = max(0, position - 1)
            continue
        decision = parse_decision(raw)
        if decision is None:
            output("Không hiểu. Dùng d, n hoặc f (thêm u nếu chưa chắc).")
            continue
        verdict, unsure = decision
        note = input_fn("Ghi chú vì sao chưa chắc (một câu): ").strip() if unsure else ""
        append_jsonl(path, {"item": item["item"], "case": item["case"], "annotator": annotator, "verdict": verdict,
                            "unsure": unsure, "note": note, "labelled_at": now_iso(), "order": position + 1})
        output(f"  -> {verdict}{'  (chưa chắc)' if unsure else ''}")
        position += 1


def wilson(k: int, n: int, z: float = 1.96) -> tuple:
    """Khoảng tin cậy Wilson 95% (z tùy chỉnh) cho một tỷ lệ k/n; (0.0, 0.0) khi n bằng 0."""
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    d = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / d
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, centre - half), min(1.0, centre + half))


def summarize(items: list, labels: dict) -> dict:
    """Đếm số liệu cho RQ2. fabricated = trích dẫn không có trong văn bản (chương trình tự xác định). Phần còn lại được đối chiếu
    với quyết định của người gán và với việc lần đọc hai giữ hay bỏ cờ.
    """
    fabricated = [i for i in items if not i["quote_ok"]]
    judged = [i for i in items if i["quote_ok"] and i["item"] in labels]
    pending = [i for i in items if i["quote_ok"] and i["item"] not in labels]
    table = {}
    for group, members in (("kept", [i for i in judged if i["kept_after_verify"]]),
                           ("removed_by_second_reading", [i for i in judged if not i["kept_after_verify"]])):
        counts = {v: sum(1 for i in members if labels[i["item"]]["verdict"] == v) for v in VERDICTS.values()}
        wrong = counts["WRONG_CODE"] + counts["NOT_A_VIOLATION"]
        table[group] = {"n": len(members), **counts, "wrong_share": wilson(wrong, len(members)), "wrong": wrong}
    return {"proposed": len(items), "fabricated_quote": len(fabricated), "judged": len(judged), "pending": len(pending), "by_group": table}


def run_summary(annotator: str, tag: str = DEFAULT_TAG, output=print) -> None:
    """In tóm tắt RQ2 của một người gán: số cờ đề xuất, trích dẫn bịa, đã gán, chưa gán, và tỷ lệ cờ sai của từng nhóm (được giữ, bị lần đọc hai loại)."""
    cases = load_cases()[1]
    items = build_items(cases, load_cache(tag))
    labels = latest(read_jsonl(path_for(annotator)))
    s = summarize(items, labels)
    output(f"Cờ mô hình đề xuất: {s['proposed']}; trích dẫn bịa (chương trình tự xác định): {s['fabricated_quote']}; "
           f"đã gán: {s['judged']}; chưa gán: {s['pending']}")
    for group, row in s["by_group"].items():
        lo, hi = row["wrong_share"]
        share = f"{row['wrong'] / row['n']:.1%} (Wilson 95%: {lo:.1%} đến {hi:.1%})" if row["n"] else "-"
        output(f"  {group}: n={row['n']}  đúng={row['CORRECT']}  sai loại={row['WRONG_CODE']}  không vi phạm={row['NOT_A_VIOLATION']}  "
               f"tỷ lệ cờ sai={share}")


def pct(k: int, n: int) -> str:
    """Định dạng k/n thành 'k/n = tỷ lệ [khoảng Wilson]' với dấu phẩy thập phân; '-' khi n bằng 0."""
    if n == 0:
        return "-"
    lo, hi = wilson(k, n)
    return f"{k}/{n} = {k / n:.1%} [{lo:.1%}; {hi:.1%}]".replace(".", ",")


def rq2_report(items: list, labels: dict) -> str:
    """Dựng báo cáo Markdown chứa mọi số RQ2 cần, từ phép kiểm tra trích dẫn của chương trình và các quyết định của người gán."""
    judged = [i for i in items if i["quote_ok"] and i["item"] in labels]
    verdict = lambda i: labels[i["item"]]["verdict"]
    correct = [i for i in judged if verdict(i) == "CORRECT"]
    wrong = [i for i in judged if verdict(i) != "CORRECT"]
    removed = [i for i in judged if not i["kept_after_verify"]]
    kept = [i for i in judged if i["kept_after_verify"]]
    fabricated = [i for i in items if not i["quote_ok"]]
    lines = ["# RQ2: cờ do mô hình đề xuất", "",
             f"Cờ đề xuất: {len(items)} (trong {len({i['case'] for i in items})} ca). Trích dẫn không có trong văn bản (bịa, chương trình tự xác định): "
             f"{pct(len(fabricated), len(items))}. Cờ có trích dẫn thật được người gán đánh giá: {len(judged)}; chưa chắc: "
             f"{sum(labels[i['item']]['unsure'] for i in judged)}.", "",
             "| Phân loại | Số cờ | Tỷ lệ trên cờ có trích dẫn thật [Wilson 95%] |", "|:--|--:|:--|"]
    for name, key in (("Đúng loại vi phạm", "CORRECT"), ("Sai loại (có vấn đề nhưng thuộc loại khác)", "WRONG_CODE"),
                      ("Không phải vi phạm (báo sai)", "NOT_A_VIOLATION")):
        k = sum(1 for i in judged if verdict(i) == key)
        lines.append(f"| {name} | {k} | {pct(k, len(judged))} |")
    lines += ["", "Từng bước loại bao nhiêu cờ sai:", "",
              "| Chỉ số | Giá trị [Wilson 95%] |", "|:--|:--|",
              f"| Cờ sai (sai loại hoặc không vi phạm) trong số cờ có trích dẫn thật | {pct(len(wrong), len(judged))} |",
              f"| Cờ sai bị lần đọc hai loại | {pct(sum(1 for i in wrong if not i['kept_after_verify']), len(wrong))} |",
              f"| Cờ đúng bị lần đọc hai loại nhầm | {pct(sum(1 for i in correct if not i['kept_after_verify']), len(correct))} |",
              f"| Trong số cờ bị lần đọc hai loại, tỷ lệ là cờ sai | {pct(sum(1 for i in removed if verdict(i) != 'CORRECT'), len(removed))} |",
              f"| Cờ sai còn lại trong số cờ được giữ | {pct(sum(1 for i in kept if verdict(i) != 'CORRECT'), len(kept))} |", "",
              "Theo mã cờ:", "", "| Mã cờ | Số cờ | Đúng | Sai loại | Không vi phạm | Bị lần đọc hai loại |", "|:--|--:|--:|--:|--:|--:|"]
    for code in sorted({i["code"] for i in judged}):
        group = [i for i in judged if i["code"] == code]
        counts = [sum(1 for i in group if verdict(i) == key) for key in ("CORRECT", "WRONG_CODE", "NOT_A_VIOLATION")]
        lines.append(f"| {code} | {len(group)} | {counts[0]} | {counts[1]} | {counts[2]} | {sum(1 for i in group if not i['kept_after_verify'])} |")
    confidences = sorted({str(i["confidence"]) for i in judged})
    lines += ["", f"Độ tin cậy mô hình tự khai: {', '.join(confidences)} (nếu chỉ có một giá trị thì không dùng để lọc được)."]
    return "\n".join(lines)


def kappa_report(first: str, second: str) -> str:
    """Kappa Cohen giữa hai người gán trên các cờ cả hai cùng đánh giá (mẫu đồng thuận), cả theo ba loại và theo gộp đúng hoặc sai."""
    from research.risk import stats
    a, b = latest(read_jsonl(path_for(first))), latest(read_jsonl(path_for(second)))
    shared = sorted(set(a) & set(b))
    if not shared:
        return f"{first} và {second} chưa có cờ nào cùng gán."
    labels = list(VERDICTS.values())
    left, right = [a[i]["verdict"] for i in shared], [b[i]["verdict"] for i in shared]
    same = sum(x == y for x, y in zip(left, right))
    right_or_not = lambda v: v == "CORRECT"
    binary = sum(right_or_not(x) == right_or_not(y) for x, y in zip(left, right))
    kappa = stats.cohen_kappa(left, right, labels)
    kappa2 = stats.cohen_kappa(["ĐÚNG" if right_or_not(x) else "SAI" for x in left], ["ĐÚNG" if right_or_not(y) else "SAI" for y in right], ["ĐÚNG", "SAI"])
    return (f"{first} và {second} cùng gán {len(shared)} cờ. Trùng nhãn ba loại: {same}/{len(shared)}, Cohen's kappa {kappa:.3f}. "
            f"Gộp thành đúng hoặc sai: trùng {binary}/{len(shared)}, kappa {kappa2:.3f}.").replace(".", ",")


def main() -> None:
    """Điểm vào dòng lệnh: chạy lệnh con label, summary, count, report hoặc agreement."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=["label", "summary", "count", "report", "agreement"])
    parser.add_argument("--sample", type=int, default=0, help="label only the shared agreement sample of this size (same items for every annotator)")
    parser.add_argument("--other", default="H2", help="agreement: the second annotator")
    parser.add_argument("--annotator", default="H1")
    parser.add_argument("--tag", default=DEFAULT_TAG)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,16}", args.annotator):
        raise SystemExit("annotator id: letters, digits, - or _ only (max 16)")
    if args.command == "label":
        run_label(args.annotator, args.tag, sample=args.sample)
    elif args.command == "agreement":
        print(kappa_report(args.annotator, args.other))
    elif args.command == "summary":
        run_summary(args.annotator, args.tag)
    elif args.command == "report":
        print(rq2_report(build_items(load_cases()[1], load_cache(args.tag)), latest(read_jsonl(path_for(args.annotator)))))
    else:
        items = build_items(load_cases()[1], load_cache(args.tag))
        print(json.dumps({"proposed": len(items), "quote_ok": sum(i["quote_ok"] for i in items),
                          "fabricated": sum(not i["quote_ok"] for i in items),
                          "kept_after_verify": sum(i["kept_after_verify"] for i in items)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
