"""Report of the adversarial set (set adv). python -m research.risk.adv_report   (no RISK_SET needed)

Detection of the disguised sale sentences by the rule layer (C0 with all of layer A, without decoding of veiled text, keywords only),
by rules plus signals (C1) and by the production path (C5); false alarms on the controls; and what happened to the model's flags in the
cases the rules missed. Labels follow from how a case is built (no annotators), so there is no kappa and no comparison with t2."""
import glob
import json
import os
from collections import Counter, defaultdict

from research.risk import flag_review as fr
from research.risk.common import HERE, load_cases, read_jsonl
from research.risk.stats import wilson

ADV_DATA = os.path.join(HERE, "data", "adv")
ADV_RESULTS = os.path.join(HERE, "results", "adv")
TAG = "adv-groq-oss20b"
NAMES = {"adv_plain": "Không biến đổi", "adv_odd_chars": "Chèn ký tự lạ", "adv_homoglyph": "Chữ giống hình", "adv_line_split": "Chia cụm qua nhiều dòng",
         "adv_inject_suppress": "Chèn lệnh để giấu vi phạm", "adv_ctrl_odd": "Đối chứng, ký tự lạ", "adv_ctrl_homoglyph": "Đối chứng, chữ giống hình",
         "adv_ctrl_line": "Đối chứng, chia cụm", "adv_ctrl_inject": "Đối chứng, chèn lệnh để gây cờ sai"}
POSITIVE = ["adv_plain", "adv_odd_chars", "adv_homoglyph", "adv_line_split", "adv_inject_suppress"]
CONTROL = ["adv_ctrl_odd", "adv_ctrl_homoglyph", "adv_ctrl_line", "adv_ctrl_inject"]
VARIANTS = {"adv_odd_chars": ("soft_hyphen", "zwnj", "word_joiner", "cgj"), "adv_homoglyph": ("cyrillic", "fullwidth"),
            "adv_line_split": ("newline_words", "hyphen_break", "syllable_lines")}
VARIANT_NAMES = {"soft_hyphen": "dấu gạch nối mềm", "zwnj": "ký tự không độ rộng ZWNJ", "word_joiner": "ký tự nối từ", "cgj": "ký tự nối đồ họa kết hợp",
                 "cyrillic": "chữ Cyrillic", "fullwidth": "chữ Latin toàn chiều rộng", "newline_words": "xuống dòng giữa hai từ",
                 "hyphen_break": "gạch nối cuối dòng", "syllable_lines": "hai chữ cái mỗi dòng"}


def vn(value: float) -> str:
    return f"{value * 100:.1f}".replace(".", ",")


def cell(k: int, n: int) -> str:
    lo, hi = wilson(k, n)
    return f"{k}/{n} ({vn(k / n)}%; [{vn(lo)}; {vn(hi)}])"


def run_rows(config: str, mode: str = "full") -> dict:
    for path in sorted(glob.glob(os.path.join(ADV_RESULTS, config, "*.jsonl")), reverse=True):
        with open(path, encoding="utf-8") as handle:
            lines = [json.loads(line) for line in handle if line.strip()]
        if lines and lines[0].get("config") == config and lines[0].get("layer_a") == mode and lines[0].get("repeats", 1) == 1:
            return {r["id"]: r for r in lines[1:] if r.get("status") == "ok"}
    raise SystemExit(f"không có kết quả {config}/{mode} của bộ adv")


def load() -> dict:
    cases = load_cases(os.path.join(ADV_DATA, "cases.jsonl"))[1]
    gold = {r["id"]: r["level"] for r in read_jsonl(os.path.join(ADV_DATA, "gold.jsonl"))}
    runs = {"C0": run_rows("C0"), "C0nd": run_rows("C0", "no_decode"), "C0bare": run_rows("C0", "bare"), "C1": run_rows("C1"), "C5": run_rows("C5")}
    return {"cases": cases, "gold": gold, "runs": runs}


def tables(d: dict) -> str:
    cases, runs = d["cases"], d["runs"]
    hit = lambda run, ids: sum(run[i]["level"] == "HIGH" for i in ids)  # noqa: E731
    raised = lambda run, ids: sum(run[i]["level"] != "LOW" for i in ids)  # noqa: E731
    ids_of = lambda g: [c["id"] for c in cases if c["group"] == g]  # noqa: E731
    out = ["| Nhóm (ca HIGH) | Số ca | Luật C0 | Luật không giải mã chữ che | C5 |", "|:--|--:|:--|:--|:--|"]
    for g in POSITIVE:
        ids = ids_of(g)
        out.append(f"| {NAMES[g]} | {len(ids)} | {cell(hit(runs['C0'], ids), len(ids))} | {cell(hit(runs['C0nd'], ids), len(ids))} | {cell(hit(runs['C5'], ids), len(ids))} |")
    pos = [i for g in POSITIVE for i in ids_of(g)]
    out.append(f"| Tất cả ca HIGH | {len(pos)} | {cell(hit(runs['C0'], pos), len(pos))} | {cell(hit(runs['C0nd'], pos), len(pos))} | {cell(hit(runs['C5'], pos), len(pos))} |")
    out += ["", "| Biến thể | Số ca | Luật C0 | C5 |", "|:--|--:|:--|:--|"]
    for g, variants in VARIANTS.items():
        for v in variants:
            ids = [c["id"] for c in cases if c["group"] == g and c["variant"] == v]
            out.append(f"| {NAMES[g]}: {VARIANT_NAMES[v]} | {len(ids)} | {hit(runs['C0'], ids)}/{len(ids)} | {hit(runs['C5'], ids)}/{len(ids)} |")
    out += ["", "| Nhóm đối chứng (ca LOW) | Số ca | Luật C0 nâng nhầm | Luật chỉ từ khóa nâng nhầm | C5 nâng nhầm |", "|:--|--:|:--|:--|:--|"]
    for g in CONTROL:
        ids = ids_of(g)
        out.append(f"| {NAMES[g]} | {len(ids)} | {raised(runs['C0'], ids)}/{len(ids)} | {raised(runs['C0bare'], ids)}/{len(ids)} | {raised(runs['C5'], ids)}/{len(ids)} |")
    ctl = [i for g in CONTROL for i in ids_of(g)]
    out.append(f"| Tất cả ca LOW | {len(ctl)} | {cell(raised(runs['C0'], ctl), len(ctl))} | {cell(raised(runs['C0bare'], ctl), len(ctl))} | {cell(raised(runs['C5'], ctl), len(ctl))} |")
    return "\n".join(out)


def flag_fate(d: dict) -> dict:
    """What happened to the model's flags in the HIGH cases the rule layer missed, and the flags raised on LOW cases."""
    cases, gold, runs = d["cases"], d["gold"], d["runs"]
    items = fr.build_items(cases, fr.load_cache(TAG))
    by_case = defaultdict(list)
    for item in items:
        by_case[item["case"]].append(item)
    missed = [c["id"] for c in cases if gold[c["id"]] == "HIGH" and runs["C0"][c["id"]]["level"] != "HIGH"]
    fate = Counter()
    for cid in missed:
        flags = by_case.get(cid, [])
        if not flags:
            fate["no_flag"] += 1
        for f in flags:
            fate["kept" if f["kept_after_verify"] else ("second_reading" if f["quote_ok"] else "quote_not_verbatim")] += 1
    rescued = sum(runs["C5"][i]["level"] == "HIGH" for i in missed)
    low_raised = [c["id"] for c in cases if gold[c["id"]] == "LOW" and runs["C5"][c["id"]]["level"] != "LOW"]
    return {"missed": len(missed), "cases_with_flag": sum(1 for i in missed if by_case.get(i)), "rescued": rescued, "fate": dict(fate),
            "low_cases_with_flag": sum(1 for c in cases if gold[c["id"]] == "LOW" and by_case.get(c["id"])), "low_raised": low_raised,
            "variants_missed": dict(Counter(next(c["variant"] for c in cases if c["id"] == i) for i in missed))}


def main() -> None:
    d = load()
    print(tables(d))
    print()
    print(json.dumps(flag_fate(d), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
