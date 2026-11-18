"""Các chỉ số của nghiên cứu, tính từ tệp kết quả của run_configs và nhãn vàng:
python -m research.risk.assessment_report --split dev [--configs C0,C1,C4,C5] [--reference C5] [--out report.md]

Đọc tệp kết quả mới nhất của mỗi cấu hình (theo --layer-a đã chọn), ghép với gold.jsonl, giữ các ca thuộc
phần chia (split) và in các bảng Markdown. Ca mà lần chạy thất bại được tính là thất bại và bị loại khỏi mọi chỉ số. Kết quả tạo
bằng --llm fake bị từ chối trừ khi có --allow-fake (đó không phải phép đo)."""
import argparse
import json
import os

from research.risk.common import CODES, GOLD_PATH, LEVELS, RESULTS, SPLIT_PATH, read_jsonl
from research.risk.stats import bootstrap_ci, confusion, holm, macro_f1, mcnemar, mean_sd, prf, wilson

ALL_CONFIGS = ("C0", "C1", "C2", "C3", "C4", "C5")


def pct(value: float) -> str:
    """Định dạng một tỷ lệ 0..1 thành phần trăm với một chữ số thập phân."""
    return f"{100 * value:.1f}%"


def with_ci(successes: int, n: int) -> str:
    """Định dạng tỷ lệ successes/n kèm khoảng tin cậy Wilson; hiện dấu - khi n = 0."""
    low, high = wilson(successes, n)
    return f"{pct(successes / n) if n else '-'} [{pct(low)}, {pct(high)}]"


def latest_run(config: str, layer_a: str, allow_fake: bool):
    """Tệp kết quả mới nhất của một cấu hình theo chế độ lớp A: trả (header, các dòng) hoặc None. Bỏ qua bản fake (trừ khi allow_fake) và bản chạy thử có limit."""
    folder = os.path.join(RESULTS, config)
    if not os.path.isdir(folder):
        return None
    for name in sorted(os.listdir(folder), reverse=True):
        if not name.endswith(f"_{layer_a}.jsonl"):
            continue
        rows = read_jsonl(os.path.join(folder, name))
        if not rows:
            continue
        header = rows[0]
        if header.get("llm") == "fake" and not allow_fake:
            continue
        if header.get("limit"):
            continue  # bản chạy thử không phải là kết quả
        return header, rows[1:]
    return None


def level_metrics(gold: list, pred: list) -> dict:
    """Chỉ số theo mức rủi ro: tp/fp/fn/precision-recall-F1 từng mức, macro F1, ma trận nhầm lẫn, số HIGH bị bỏ sót (và bị xuống LOW) và số báo sai HIGH."""
    n = len(gold)
    per_level = {}
    for level in LEVELS:
        tp = sum(1 for g, p in zip(gold, pred) if g == level and p == level)
        fp = sum(1 for g, p in zip(gold, pred) if g != level and p == level)
        fn = sum(1 for g, p in zip(gold, pred) if g == level and p != level)
        per_level[level] = {"tp": tp, "fp": fp, "fn": fn, "prf": prf(tp, fp, fn), "support": tp + fn}
    n_high = sum(1 for g in gold if g == "HIGH")
    n_rest = n - n_high
    return {
        "n": n, "accuracy": sum(1 for g, p in zip(gold, pred) if g == p), "per_level": per_level,
        "macro_f1": macro_f1(gold, pred, LEVELS), "confusion": confusion(gold, pred, LEVELS),
        "miss_high": (sum(1 for g, p in zip(gold, pred) if g == "HIGH" and p != "HIGH"), n_high),
        "miss_high_to_low": (sum(1 for g, p in zip(gold, pred) if g == "HIGH" and p == "LOW"), n_high),
        "false_high": (sum(1 for g, p in zip(gold, pred) if g != "HIGH" and p == "HIGH"), n_rest),
    }


def code_metrics(gold_codes: list, pred_codes: list) -> dict:
    """Với mỗi mã vi phạm, trả (số ca thật, precision, recall, F1) so giữa mã của nhãn vàng và mã dự đoán."""
    out = {}
    for code in CODES:
        tp = sum(1 for g, p in zip(gold_codes, pred_codes) if code in g and code in p)
        fp = sum(1 for g, p in zip(gold_codes, pred_codes) if code not in g and code in p)
        fn = sum(1 for g, p in zip(gold_codes, pred_codes) if code in g and code not in p)
        out[code] = (tp + fn, *prf(tp, fp, fn))
    return out


def stability(rows: list) -> dict:
    """Trên các lần chạy lặp của cùng một ca: độ lệch chuẩn trung bình của điểm, và số ca mà mức không giống nhau ở mọi lần."""
    by_case = {}
    for row in rows:
        by_case.setdefault(row["id"], []).append(row)
    sds, changed, usable = [], 0, 0
    for items in by_case.values():
        if len(items) < 2:
            continue
        usable += 1
        sds.append(mean_sd([r["score"] for r in items])[1])
        changed += len({r["level"] for r in items}) > 1
    return {"cases": usable, "mean_sd": sum(sds) / len(sds) if sds else 0.0, "changed": changed}


def select_ids(split: str, gold_ids: set) -> set:
    """Tập id ca thuộc phần chia đã chọn (dev, test hoặc all); thoát với lỗi nếu chưa dựng split.json."""
    if split == "all":
        return gold_ids
    if not os.path.exists(SPLIT_PATH):
        raise SystemExit(f"{SPLIT_PATH} missing: build the split from the gold labels first")
    with open(SPLIT_PATH, encoding="utf-8") as handle:
        return gold_ids & set(json.load(handle)[split])


def build_report(split: str, configs: list, layer_a: str, reference: str, allow_fake: bool) -> str:
    """Dựng báo cáo Markdown: mức rủi ro, precision/recall/F1, ma trận nhầm lẫn, theo mã vi phạm, lớp mô hình, độ ổn định và so sánh cặp McNemar với cấu hình tham chiếu."""
    gold = {row["id"]: row for row in read_jsonl(GOLD_PATH)}
    if not gold:
        raise SystemExit(f"{GOLD_PATH} is empty or missing: label the cases and build the gold first")
    wanted = select_ids(split, set(gold))
    lines = [f"# Kết quả thực nghiệm: split={split}, lớp A={layer_a}, {len(wanted)} ca có nhãn chuẩn", ""]
    runs, prediction = {}, {}
    for config in configs:
        found = latest_run(config, layer_a, allow_fake)
        if found is None:
            lines.append(f"- {config}: chưa có kết quả (hoặc chỉ có bản chạy thử hoặc fake).")
            continue
        runs[config] = found
        header, rows = found
        first = {r["id"]: r for r in rows if r["repeat"] == 0 and r["id"] in wanted}
        prediction[config] = first
    lines.append("")
    if not runs:
        return "\n".join(lines)

    lines += ["## Mức rủi ro", "", "| Cấu hình | n | Thất bại | Chính xác | Macro F1 [95% bootstrap] | Bỏ sót HIGH | Bỏ sót HIGH thành LOW | Báo sai HIGH | Mô hình |",
              "|:--|--:|--:|:--|:--|:--|:--|:--|:--|"]
    correct_by_config, details = {}, {}
    for config, (header, rows) in runs.items():
        ok = {i: r for i, r in prediction[config].items() if r["status"] == "ok"}
        failed = sum(1 for r in prediction[config].values() if r["status"] == "failed")
        ids = sorted(ok)
        g, p = [gold[i]["level"] for i in ids], [ok[i]["level"] for i in ids]
        if not ids:
            lines.append(f"| {config} | 0 | {failed} | - | - | - | - | - | - |")
            continue
        m = level_metrics(g, p)
        details[config] = (ids, g, p, m, ok)
        correct_by_config[config] = dict(zip(ids, (a == b for a, b in zip(g, p))))
        low, high = bootstrap_ci(lambda pairs: macro_f1([x for x, _ in pairs], [y for _, y in pairs], LEVELS), list(zip(g, p)), 2000)
        lines.append(f"| {config} | {m['n']} | {failed} | {with_ci(m['accuracy'], m['n'])} | {pct(m['macro_f1'])} [{pct(low)}, {pct(high)}] | "
                     f"{with_ci(*m['miss_high'])} | {with_ci(*m['miss_high_to_low'])} | {with_ci(*m['false_high'])} | {', '.join(header.get('models') or ['-'])} |")
    lines.append("")

    lines += ["## Precision, recall, F1 theo mức", "", "| Cấu hình | Mức | Số ca thật | Precision | Recall | F1 |", "|:--|:--|--:|--:|--:|--:|"]
    for config, (ids, g, p, m, ok) in details.items():
        for level in LEVELS:
            entry = m["per_level"][level]
            lines.append(f"| {config} | {level} | {entry['support']} | {pct(entry['prf'][0])} | {pct(entry['prf'][1])} | {pct(entry['prf'][2])} |")
    lines += ["", "## Ma trận nhầm lẫn (hàng: nhãn chuẩn, cột: dự đoán)", ""]
    for config, (ids, g, p, m, ok) in details.items():
        lines += [f"**{config}**", "", "| | " + " | ".join(LEVELS) + " |", "|:--|" + "--:|" * len(LEVELS)]
        lines += [f"| {gl} | " + " | ".join(str(m["confusion"][gl][pl]) for pl in LEVELS) + " |" for gl in LEVELS]
        lines.append("")

    lines += ["## Theo mã vi phạm (cấu hình có trả mã)", "", "| Cấu hình | Mã | Số ca thật | Precision | Recall | F1 |", "|:--|:--|--:|--:|--:|--:|"]
    for config, (ids, g, p, m, ok) in details.items():
        if config == "C2":
            continue
        for code, (support, pr, rc, f1) in code_metrics([gold[i]["codes"] for i in ids], [ok[i]["codes"] for i in ids]).items():
            lines.append(f"| {config} | {code} | {support} | {pct(pr)} | {pct(rc)} | {pct(f1)} |")

    lines += ["", "## Lớp mô hình", "", "| Cấu hình | Cờ đề xuất | Cờ giữ lại | Bị loại ở đọc lần hai | Trùng luật bị bỏ | Độ trễ TB (ms) | p95 (ms) |", "|:--|--:|--:|--:|--:|--:|--:|"]
    for config, (ids, g, p, m, ok) in details.items():
        if config not in ("C3", "C4", "C5"):
            continue
        proposed = sum(ok[i]["flags_proposed"] for i in ids)
        kept = sum(ok[i]["flags_kept"] for i in ids)
        removed = sum(len(ok[i]["ai_meta"].get("verify_removed", [])) for i in ids)
        dropped = sum(ok[i].get("overlap_dropped", 0) for i in ids)
        times = sorted(ok[i]["latency_ms"] for i in ids)
        lines.append(f"| {config} | {proposed} | {kept} | {removed} | {dropped} | {sum(times) / len(times):.0f} | {times[int(0.95 * (len(times) - 1))]:.0f} |")
    lines.append("*Độ trễ có thể gồm cả câu trả lời lấy từ bộ nhớ đệm; chỉ số độ trễ thật nằm ở cột llm_ms của tệp kết quả.*")

    stable = [(c, stability([r for r in rows if r["status"] == "ok" and r["id"] in wanted])) for c, (h, rows) in runs.items() if h.get("repeats", 1) > 1]
    if stable:
        lines += ["", "## Độ ổn định khi chạy lặp", "", "| Cấu hình | Số ca lặp | SD điểm trung bình | Số ca đổi mức giữa các lần |", "|:--|--:|--:|--:|"]
        lines += [f"| {c} | {s['cases']} | {s['mean_sd']:.2f} | {s['changed']} ({pct(s['changed'] / s['cases']) if s['cases'] else '-'}) |" for c, s in stable]

    if reference in correct_by_config:
        lines += ["", f"## So sánh cặp với {reference} (McNemar chính xác, hiệu chỉnh Holm)", "",
                  "| Cấu hình | Chỉ cấu hình đúng | Chỉ " + reference + " đúng | p | p (Holm) |", "|:--|--:|--:|--:|--:|"]
        others = [c for c in correct_by_config if c != reference]
        tests = []
        for config in others:
            shared = sorted(set(correct_by_config[config]) & set(correct_by_config[reference]))
            tests.append((config, mcnemar([correct_by_config[config][i] for i in shared], [correct_by_config[reference][i] for i in shared])))
        adjusted = holm([t["p"] for _, t in tests])
        for (config, t), q in zip(tests, adjusted):
            lines.append(f"| {config} | {t['only_a']} | {t['only_b']} | {t['p']:.4f} | {q:.4f} |")
    return "\n".join(lines) + "\n"


def main() -> None:
    """Điểm vào dòng lệnh: dựng báo cáo, ghi ra tệp --out (nếu có) và in ra màn hình."""
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--split", choices=("dev", "test", "all"), default="dev")
    parser.add_argument("--configs", default=",".join(ALL_CONFIGS))
    parser.add_argument("--layer-a", default="full")
    parser.add_argument("--reference", default="C5")
    parser.add_argument("--allow-fake", action="store_true")
    parser.add_argument("--out")
    args = parser.parse_args()
    report = build_report(args.split, [c.strip() for c in args.configs.split(",")], args.layer_a, args.reference, args.allow_fake)
    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(report)
    print(report)


if __name__ == "__main__":
    main()
