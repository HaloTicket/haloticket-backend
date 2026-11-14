"""Tables of the paper's section F from the result files. RISK_SET=t2 python -m research.risk.paper_tables [--csv PATH]

Prints (1) the data table and (2) the per-case result table of C5 for ten cases, (3) C5 errors by group, and writes a CSV with
all 500 cases for the appendix. The ten cases: one per group, for the ten largest groups, picked with a fixed seed; the choice
never looks at any result."""
import argparse
import csv
import glob
import hashlib
import json
import os
import random
from collections import Counter, defaultdict

from research.risk.common import GOLD_PATH, RESULTS, load_cases, read_jsonl

GROUP_NAMES = {
    "clean": "Sạch", "ban_context": "Điều khoản cấm dài (hợp lệ)", "contact_allowed": "Liên hệ được phép",
    "prohibited_explicit": "Hàng cấm nói thẳng", "prohibited_veiled": "Hàng cấm viết che ký tự",
    "prohibited_semantic": "Hàng cấm nói vòng bằng ngữ nghĩa", "social_explicit": "Liên hệ ngoài nền tảng, nêu tên kênh",
    "social_veiled": "Liên hệ ngoài nền tảng, không nêu tên kênh", "scam": "Lừa đảo", "overpromise": "Hứa quá mức",
    "price_anomaly": "Giá bất thường", "price_few_samples": "Giá, thiếu mẫu so sánh", "near_duplicate": "Gần trùng sự kiện đã có",
    "organizer_risk": "Rủi ro ban tổ chức", "schedule_anomaly": "Lịch bất thường", "injection": "Chèn lệnh vào mô tả",
    "mixed": "Nhiều vi phạm", "borderline": "Ranh giới",
}
SEED = "paper-table-cases"


def c5_rows() -> dict:
    for path in sorted(glob.glob(os.path.join(RESULTS, "C5", "*_full.jsonl")), reverse=True):
        with open(path, encoding="utf-8") as handle:
            lines = [json.loads(line) for line in handle if line.strip()]
        if lines[0].get("repeats") == 1 and lines[0].get("llm") == "live":
            return {r["id"]: r for r in lines[1:]}
    raise SystemExit("không có lần chạy C5 k=1")


def pick_cases(cases: list, count: int = 10) -> list:
    sizes = Counter(c["group"] for c in cases)
    groups = [g for g, _ in sorted(sizes.items(), key=lambda x: (-x[1], x[0]))[:count]]
    rng = random.Random(int(hashlib.sha256(SEED.encode("utf-8")).hexdigest()[:12], 16))
    picked = []
    for group in groups:
        ids = sorted(c["id"] for c in cases if c["group"] == group)
        rng.shuffle(ids)
        picked.append(ids[0])
    return picked


def fmt_codes(codes: list) -> str:
    return ", ".join(codes) if codes else "-"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--csv", default=os.path.join(RESULTS, "per_case_C5.csv"))
    run(parser.parse_args().csv)


def run(csv_path: str) -> None:
    cases = load_cases()[1]
    by = {c["id"]: c for c in cases}
    gold = {r["id"]: r for r in read_jsonl(GOLD_PATH)}
    rows = c5_rows()

    with open(csv_path, "w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["id", "nhóm", "nhãn chuẩn (mức)", "mã nhãn chuẩn", "cách chốt nhãn", "H1", "H2", "L", "điểm luật", "điểm tín hiệu",
                         "cờ AI đề xuất", "cờ AI giữ", "điểm AI", "điểm cuối", "mức dự đoán", "đủ lớp", "đúng"])
        for cid in sorted(rows):
            r, g = rows[cid], gold[cid]
            writer.writerow([cid, GROUP_NAMES.get(by[cid]["group"], by[cid]["group"]), g["level"], fmt_codes(g["codes"]), g["method"],
                             g["votes"]["H1"], g["votes"]["H2"], g["votes"]["L"], r["rule_score"], r["signal_score"], r["flags_proposed"],
                             r["flags_kept"], r["ai_score"], r["score"], r["level"], "có" if r["complete"] else "không",
                             "đúng" if r["level"] == g["level"] else "sai"])

    picked = pick_cases(cases)
    print("| Mã ca | Nhóm | Nguồn | Nhãn chuẩn (mức; mã) | H1, H2 cùng mức |")
    print("|:--|:--|:--|:--|:--|")
    for cid in picked:
        g = gold[cid]
        same = "có" if g["votes"]["H1"] == g["votes"]["H2"] else f"không (H1 {g['votes']['H1']}, H2 {g['votes']['H2']})"
        print(f"| {cid} | {GROUP_NAMES.get(by[cid]['group'])} | Bộ sinh tự động | {g['level']}; {fmt_codes(g['codes'])} | {same} |")
    print()
    print("| Mã ca | Điểm luật | Điểm tín hiệu | Cờ AI đề xuất | Cờ AI giữ sau kiểm chứng | Điểm AI | Điểm cuối | Mức dự đoán | Đúng hay sai |")
    print("|:--|--:|--:|--:|--:|--:|--:|:--|:--|")
    for cid in picked:
        r, g = rows[cid], gold[cid]
        verdict = "đúng" if r["level"] == g["level"] else f"sai (nhãn chuẩn {g['level']})"
        print(f"| {cid} | {r['rule_score']} | {r['signal_score']} | {r['flags_proposed']} | {r['flags_kept']} | {r['ai_score']} | {r['score']} | {r['level']} | {verdict} |")
    print()
    total, wrong = Counter(), Counter()
    for cid, r in rows.items():
        group = by[cid]["group"]
        total[group] += 1
        wrong[group] += r["level"] != gold[cid]["level"]
    print(f"C5 sai {sum(wrong.values())}/{len(rows)}. Sai theo nhóm (chỉ các nhóm có ca sai):")
    print("| Nhóm | Số ca | Số ca sai | Tỷ lệ sai |")
    print("|:--|--:|--:|--:|")
    for group in sorted(total, key=lambda g: (-wrong[g], g)):
        if wrong[group]:
            print(f"| {GROUP_NAMES.get(group, group)} | {total[group]} | {wrong[group]} | {wrong[group] / total[group]:.1%} |".replace(".", ","))
    print(f"\nCSV: {csv_path}")


if __name__ == "__main__":
    main()
