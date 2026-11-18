"""Đối chiếu các số gõ trong bản thảo (docs/nckh-risk-scoring-report.md) với tệp kết quả.
    RISK_SET=t2 python -m research.risk.check_draft [--draft PATH]

Đọc báo cáo đã sinh (results/t2/report_t2_*.md), các dòng thô (results/t2/C*/..._full.jsonl) và bản thảo, rồi in
mọi ô bị lệch. Dấu phẩy thập phân trong bản thảo và dấu chấm trong báo cáo được coi là cùng một số. Thoát với mã 1 khi
có chỗ lệch, nên có thể chạy trước khi nộp bài.
"""
import argparse
import glob
import json
import os
import re
import sys

from research.risk.common import RESULTS

DRAFT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "docs", "nckh-risk-scoring-report.md"))


def num(text: str):
    """Lấy số đầu tiên trong một ô thành float, ví dụ '67,8% [63,7; 71,5]' cho 67.8. Trả về None khi không có số."""
    match = re.search(r"-?\d+(?:[.,]\d+)?", text)
    return float(match.group(0).replace(",", ".")) if match else None


def rows_of(markdown: str, start_pattern: str) -> list:
    """Lấy các ô của bảng đầu tiên nằm sau dòng khớp với start_pattern; trả về danh sách rỗng nếu không thấy dòng đó."""
    lines = markdown.splitlines()
    start = next((i for i, line in enumerate(lines) if re.search(start_pattern, line)), None)
    if start is None:
        return []
    table, seen = [], False
    for line in lines[start + 1:]:
        if line.startswith("|"):
            seen = True
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if not all(set(c) <= set(":-") for c in cells):
                table.append(cells)
        elif seen:
            break
    return table


def latest_full(config: str, repeats: int = 1):
    """Tìm tệp kết quả đầy đủ mới nhất của một cấu hình có đúng số lần lặp và chạy bằng mô hình thật.

    Args:
        config: tên cấu hình (C0 đến C5).
        repeats: số lần lặp cần khớp.

    Returns:
        (dòng tiêu đề, các dòng kết quả), hoặc (None, []) nếu không có tệp phù hợp.
    """
    for path in sorted(glob.glob(os.path.join(RESULTS, config, "*_full.jsonl")), reverse=True):
        with open(path, encoding="utf-8") as handle:
            rows = [json.loads(line) for line in handle if line.strip()]
        if rows and rows[0].get("repeats", 1) == repeats and rows[0].get("llm") == "live":
            return rows[0], rows[1:]
    return None, []


def compare(label: str, draft, report, problems: list, tol: float = 0.05) -> None:
    """So một số của bản thảo với số của báo cáo; thêm một dòng vào problems khi thiếu số hoặc chênh quá dung sai.

    Args:
        label: tên của số đang so, dùng trong thông báo.
        draft: số trong bản thảo (có thể None).
        report: số trong báo cáo (có thể None).
        problems: danh sách các chỗ lệch, được thêm vào tại chỗ.
        tol: dung sai tuyệt đối cho phép.
    """
    if draft is None or report is None:
        problems.append(f"{label}: thiếu số (bản thảo={draft}, báo cáo={report})")
    elif abs(draft - report) > tol:
        problems.append(f"{label}: bản thảo {draft} khác báo cáo {report}")


def main_table(draft: str, report: str, problems: list) -> int:
    """So bảng kết quả sáu cấu hình của bản thảo với mục 'Mức rủi ro' của báo cáo (Macro F1, độ chính xác, bỏ sót và báo sai HIGH).

    Returns:
        số ô đã so.
    """
    d, r = rows_of(draft, r"Kết quả sáu cấu hình"), rows_of(report, r"## Mức rủi ro")
    columns = {1: "Macro F1", 2: "Chính xác", 3: "Bỏ sót HIGH", 4: "Bỏ sót HIGH thành LOW", 5: "Báo sai HIGH"}
    report_cols = {1: 4, 2: 3, 3: 5, 4: 6, 5: 7}  # cột của bản thảo -> cột của báo cáo
    by_report = {row[0]: row for row in r[1:]}
    checked = 0
    for row in d[1:]:
        name = row[0].split()[0]
        if name not in by_report:
            problems.append(f"bảng chính: không có {name} trong báo cáo")
            continue
        for col, label in columns.items():
            compare(f"{name} {label}", num(row[col]), num(by_report[name][report_cols[col]]), problems)
            checked += 1
    return checked


def confusion_c5(draft: str, report: str, problems: list) -> int:
    """So ma trận nhầm lẫn của C5 trong bản thảo với báo cáo, từng ô phải khớp tuyệt đối.

    Returns:
        số ô đã so.
    """
    d = rows_of(draft, r"Ma trận nhầm lẫn của C5")
    r = rows_of(report, r"^\*\*C5\*\*\s*$")
    checked = 0
    for dr, rr in zip(d[1:], r[1:]):
        for index in (1, 2, 3):
            compare(f"ma trận C5 {dr[0]}/{index}", num(dr[index]), num(rr[index]), problems, tol=0)
            checked += 1
    return checked


def raw_claims(draft: str, problems: list) -> int:
    """Kiểm các câu trong bản thảo lấy thẳng từ dữ liệu thô: số cờ bị lần đọc hai loại bỏ và số cờ trùng luật bị bỏ.

    Returns:
        số số liệu đã so.
    """
    header, rows = latest_full("C5", 1)
    if not rows:
        problems.append("C5: không tìm thấy lần chạy k=1")
        return 0
    removed = sum(len((r.get("ai_meta") or {}).get("verify_removed") or []) for r in rows)
    overlap = sum(r.get("overlap_dropped") or 0 for r in rows)
    checked = 0
    match = re.search(r"lần đọc hai loại (\d+) cờ và (\d+) cờ trùng luật", draft)
    if match:
        compare("cờ bị lần đọc hai loại", float(match.group(1)), float(removed), problems, tol=0)
        compare("cờ trùng luật bị bỏ", float(match.group(2)), float(overlap), problems, tol=0)
        checked += 2
    else:
        problems.append("không tìm thấy câu 'lần đọc hai loại N cờ và M cờ trùng luật' trong bản thảo")
    return checked


def _vn(value: float, digits: int = 1) -> str:
    """Định dạng số theo kiểu Việt Nam: dấu phẩy thập phân với số chữ số cho trước."""
    return f"{value:.{digits}f}".replace(".", ",")


def _ci(k: int, n: int) -> str:
    """Viết một tỷ lệ k/n kèm khoảng tin cậy Wilson theo đúng kiểu bản thảo, ví dụ '62,5%; [30,6; 86,3]'."""
    from research.risk.stats import wilson
    lo, hi = wilson(k, n)
    return f"{_vn(k / n * 100)}%; [{_vn(lo * 100)}; {_vn(hi * 100)}]"


def generated_tables(draft: str, problems: list) -> int:
    """Mọi dòng của các bảng do các script in ra (paper_tables, stability, prevalence, rq4) phải có trong bản thảo, từng chữ.

    Returns:
        số dòng bảng đã so.
    """
    import contextlib
    import io
    from research.risk import paper_tables, prevalence, rq4, stability
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        paper_tables.run(os.path.join(RESULTS, "per_case_C5.csv"))
    sources = {"mục F (paper_tables)": buffer.getvalue(), "độ ổn định (stability)": stability.table(), "precision theo tỷ lệ (prevalence)": prevalence.table(),
               "RQ4 (rq4)": rq4.table()}
    in_draft = set(draft.splitlines())
    checked = 0
    for name, text in sources.items():
        for line in text.splitlines():
            if line.startswith("|"):
                checked += 1
                if line not in in_draft:
                    problems.append(f"{name}: dòng không có trong bản thảo: {line[:110]}")
    return checked


def rq2_and_injection_claims(draft: str, problems: list) -> int:
    """Kiểm các câu về RQ2 (chất lượng cờ của mô hình, kappa của hai người gán) và về nhóm injection xem có khớp dữ liệu không.

    Returns:
        số câu đã kiểm.
    """
    from research.risk import flag_review as fr
    from research.risk.common import GOLD_PATH, load_cases, read_jsonl
    from research.risk.prevalence import latest_rows
    items = fr.build_items(load_cases()[1], fr.load_cache(fr.DEFAULT_TAG))
    labels = fr.latest(read_jsonl(fr.path_for("H1")))
    judged = [i for i in items if i["quote_ok"] and i["item"] in labels]
    verdict = lambda i: labels[i["item"]]["verdict"]  # noqa: E731
    kept = [i for i in judged if i["kept_after_verify"]]
    removed = [i for i in judged if not i["kept_after_verify"]]
    count = lambda group, key: sum(1 for i in group if verdict(i) == key)  # noqa: E731
    claims = {
        "cờ bịa trích dẫn": f"{len(items) - len(judged)} cờ ({_vn((len(items) - len(judged)) / len(items) * 100)}%; Wilson 95%: {_ci(len(items) - len(judged), len(items)).split('[')[1].replace('; ', ' đến ').rstrip(']')}%)",
        "cờ trích đúng nội dung vi phạm": f"{len(judged) - count(judged, 'NOT_A_VIOLATION')} trong {len(judged)} cờ ({_ci(len(judged) - count(judged, 'NOT_A_VIOLATION'), len(judged))})",
        "lần đọc hai loại cờ không phải vi phạm": f"{count(removed, 'NOT_A_VIOLATION')} trong {count(judged, 'NOT_A_VIOLATION')} cờ không phải vi phạm ({_ci(count(removed, 'NOT_A_VIOLATION'), count(judged, 'NOT_A_VIOLATION'))})",
        "lần đọc hai loại cờ sai loại": f"{count(removed, 'WRONG_CODE')} trong {count(judged, 'WRONG_CODE')} cờ sai loại ({_ci(count(removed, 'WRONG_CODE'), count(judged, 'WRONG_CODE'))})",
        "lần đọc hai loại nhầm cờ đúng": f"{count(removed, 'CORRECT')} trong {count(judged, 'CORRECT')} cờ đúng ({_ci(count(removed, 'CORRECT'), count(judged, 'CORRECT'))})",
        "cờ được giữ vẫn không phải vi phạm": f"{count(kept, 'NOT_A_VIOLATION')} trong {len(kept)} cờ được giữ vẫn không phải vi phạm ({_ci(count(kept, 'NOT_A_VIOLATION'), len(kept))})",
        "cờ được giữ vẫn mang mã sai": f"{count(kept, 'WRONG_CODE')} cờ ({_ci(count(kept, 'WRONG_CODE'), len(kept))}) vẫn mang mã sai",
        "bảng nhãn đúng loại": f"| Đúng loại vi phạm | {count(judged, 'CORRECT')} | {_ci(count(judged, 'CORRECT'), len(judged)).replace('%; ', '% ')} |",
        "bảng nhãn sai loại": f"| Sai loại | {count(judged, 'WRONG_CODE')} | {_ci(count(judged, 'WRONG_CODE'), len(judged)).replace('%; ', '% ')} |",
        "bảng nhãn không vi phạm": f"| Không phải vi phạm | {count(judged, 'NOT_A_VIOLATION')} | {_ci(count(judged, 'NOT_A_VIOLATION'), len(judged)).replace('%; ', '% ')} |",
    }
    h2 = fr.latest(read_jsonl(fr.path_for("H2")))
    shared = sorted(set(labels) & set(h2))
    if shared:
        from research.risk import stats
        same = sum(labels[i]["verdict"] == h2[i]["verdict"] for i in shared)
        kappa = stats.cohen_kappa([labels[i]["verdict"] for i in shared], [h2[i]["verdict"] for i in shared], list(fr.VERDICTS.values()))
        claims["H1 và H2 trùng nhãn"] = f"trùng nhãn {same}/{len(shared)}"
        claims["kappa của H1 và H2"] = f"Cohen's kappa {_vn(kappa, 3)}"
    gold = {r["id"]: r["level"] for r in read_jsonl(GOLD_PATH)}
    inject = [c["id"] for c in load_cases()[1] if c["group"] == "injection"]
    for config in ("C0", "C1", "C2", "C3", "C4", "C5"):
        rows = latest_rows(config)
        claims[f"injection {config}"] = f"{sum(rows[i]['level'] == gold[i] for i in inject)} trong {len(inject)}"
    for name, expected in claims.items():
        if not name.startswith("injection "):
            if expected not in draft:
                problems.append(f"RQ2, {name}: bản thảo không có đúng chuỗi: {expected}")
    ok = {name.split()[1]: int(expected.split()[0]) for name, expected in claims.items() if name.startswith("injection ")}
    # bản thảo viết: C0, C1, C3, C4 và C5 đúng hết, C2 đúng 14 trong 15
    sentence = "C0, C1, C3, C4 và C5 xếp đúng 15 trong 15 ca"
    if len(inject) != 15 or any(ok[c] != 15 for c in ("C0", "C1", "C3", "C4", "C5")) or ok["C2"] != 14 or sentence not in draft \
            or "C2 xếp đúng 14 trong 15 ca" not in draft:
        problems.append(f"nhóm injection: số ca đúng thực tế {ok} (trên {len(inject)} ca) khác câu trong bản thảo")
    return len(claims)


def adversarial_checks(draft: str, problems: list) -> int:
    """Kiểm ba bảng bộ đối kháng từng chữ, và các câu mang số liệu về số phận của các cờ do mô hình đề xuất.

    Returns:
        số mục đã kiểm.
    """
    from research.risk import adv_report
    data = adv_report.load()
    in_draft = set(draft.splitlines())
    checked = 0
    for line in adv_report.tables(data).splitlines():
        if line.startswith("|"):
            checked += 1
            if line not in in_draft:
                problems.append(f"bộ đối kháng: dòng không có trong bản thảo: {line[:110]}")
    fate = adv_report.flag_fate(data)
    f = fate["fate"]
    pos = [c["id"] for c in data["cases"] if data["gold"][c["id"]] == "HIGH"]
    c0 = sum(data["runs"]["C0"][i]["level"] == "HIGH" for i in pos)
    c5 = sum(data["runs"]["C5"][i]["level"] == "HIGH" for i in pos)
    claims = [
        f"Trong {fate['missed']} ca HIGH mà luật bỏ sót, mô hình đề xuất cờ ở {fate['cases_with_flag']} ca và không đề xuất cờ ở {f.get('no_flag', 0)} ca",
        f"loại {f.get('quote_not_verbatim', 0)} trong {fate['cases_with_flag']} cờ vì lý do này, lần đọc hai loại {f.get('second_reading', 0)} cờ, và {f.get('kept', 0)} cờ được giữ, nên C5 cứu lại {fate['rescued']} ca",
        f"C0 bắt {c0} ({adv_report.vn(c0 / len(pos))}%) và C5 bắt {c5} ({adv_report.vn(c5 / len(pos))}%)",
        f"C5 nâng nhầm {len(fate['low_raised'])} ca trong {sum(1 for c in data['cases'] if data['gold'][c['id']] == 'LOW')}",
    ]
    for claim in claims:
        checked += 1
        if claim not in draft:
            problems.append(f"bộ đối kháng: bản thảo không có đúng chuỗi: {claim}")
    return checked


def main() -> None:
    """Đọc báo cáo mới nhất và bản thảo, chạy mọi phép đối chiếu rồi in các chỗ lệch; thoát với mã 1 nếu có."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--draft", default=DRAFT)
    args = parser.parse_args()
    report_files = sorted(glob.glob(os.path.join(RESULTS, "report_*.md")))
    if not report_files:
        raise SystemExit("chưa có results/<set>/report_*.md: chạy research.risk.assessment_report trước")
    with open(report_files[-1], encoding="utf-8") as handle:
        report = handle.read()
    with open(args.draft, encoding="utf-8") as handle:
        draft = handle.read()
    problems: list = []
    checked = main_table(draft, report, problems) + confusion_c5(draft, report, problems) + raw_claims(draft, problems)
    checked += generated_tables(draft, problems) + rq2_and_injection_claims(draft, problems) + adversarial_checks(draft, problems)
    print(f"Đã so {checked} số của bản thảo với {os.path.basename(report_files[-1])} và dữ liệu thô.")
    if problems:
        print(f"{len(problems)} chỗ khác:")
        for item in problems:
            print("  -", item)
        sys.exit(1)
    print("Không có số nào lệch.")


if __name__ == "__main__":
    main()
