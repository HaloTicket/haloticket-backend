"""Các bảng của mục F trong bài báo, lập từ tệp kết quả. RISK_SET=t2 python -m research.risk.paper_tables [--csv PATH]

In (1) bảng dữ liệu và (2) bảng kết quả từng ca của C5 cho mười ca, (3) lỗi của C5 theo nhóm, và ghi một tệp CSV có
đủ 500 ca cho phụ lục. Mười ca: mỗi nhóm một ca, lấy từ mười nhóm lớn nhất, chọn bằng hạt giống cố định; việc chọn
không bao giờ nhìn vào kết quả nào.
"""
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
    """Đọc kết quả C5 đầy đủ mới nhất có một lần lặp và chạy bằng mô hình thật.

    Returns:
        từ điển {mã ca: dòng kết quả}.

    Raises:
        SystemExit: khi không có lần chạy C5 k=1.
    """
    for path in sorted(glob.glob(os.path.join(RESULTS, "C5", "*_full.jsonl")), reverse=True):
        with open(path, encoding="utf-8") as handle:
            lines = [json.loads(line) for line in handle if line.strip()]
        if lines[0].get("repeats") == 1 and lines[0].get("llm") == "live":
            return {r["id"]: r for r in lines[1:]}
    raise SystemExit("không có lần chạy C5 k=1")


def pick_cases(cases: list, count: int = 10) -> list:
    """Chọn mỗi nhóm lớn nhất một ca (theo hạt giống cố định, không phụ thuộc kết quả) để đưa vào bảng bài báo.

    Args:
        cases: danh sách ca.
        count: số nhóm lớn nhất cần lấy.

    Returns:
        danh sách mã ca đã chọn.
    """
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
    """Nối các mã bằng dấu phẩy; trả về '-' khi danh sách rỗng."""
    return ", ".join(codes) if codes else "-"


def main() -> None:
    """Điểm vào dòng lệnh: đọc tùy chọn --csv rồi chạy run."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--csv", default=os.path.join(RESULTS, "per_case_C5.csv"))
    run(parser.parse_args().csv)


def run(csv_path: str) -> None:
    """Ghi CSV 500 ca của C5 cho phụ lục, rồi in ba bảng Markdown: dữ liệu mười ca, kết quả C5 của mười ca đó, và tỷ lệ sai theo nhóm.

    Args:
        csv_path: đường dẫn tệp CSV sẽ ghi.
    """
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
