"""Precision của mức HIGH ở các tỷ lệ vi phạm khác. RISK_SET=t2 python -m research.risk.prevalence

Bộ t2 có 30,8% ca HIGH, nhiều hơn xa so với thực tế của một nền tảng. Recall của HIGH (TPR) và tỷ lệ ca không HIGH bị nâng lên
HIGH (FPR) không phụ thuộc số ca mỗi loại, nên precision ở tỷ lệ pi là
    precision(pi) = TPR*pi / (TPR*pi + FPR*(1-pi)).
Khoảng tin cậy lấy cận Wilson 95% của TPR và FPR theo hướng xấu nhất và tốt nhất, nên mang tính thận trọng.
Nó giả định các ca không HIGH của t2 đại diện cho các ca không HIGH của nền tảng; t2 là dữ liệu sinh ra, vì vậy đây là
ước lượng phụ thuộc vào giả định đó chứ không phải phép đo trên lưu lượng thật.
"""
import glob
import json
import os

from research.risk.common import GOLD_PATH, RESULTS, read_jsonl
from research.risk.stats import wilson

RATES = (0.01, 0.05, 0.10)


def latest_rows(config: str) -> dict:
    """Lấy các dòng kết quả mới nhất của một cấu hình trên toàn bộ tập, một lần lặp, lớp A đầy đủ (C2 đến C5 phải chạy bằng mô hình thật), chỉ các ca trạng thái ok.

    Raises:
        SystemExit: khi không có lần chạy phù hợp.
    """
    for path in sorted(glob.glob(os.path.join(RESULTS, config, "*_full.jsonl")), reverse=True):
        with open(path, encoding="utf-8") as handle:
            lines = [json.loads(line) for line in handle if line.strip()]
        head = lines[0]
        live_ok = head.get("llm") == "live" or config in ("C0", "C1")
        if head.get("repeats") == 1 and live_ok and head.get("layer_a") == "full" and head.get("split") == "all":
            return {r["id"]: r for r in lines[1:] if r.get("status") == "ok"}
    raise SystemExit(f"không có lần chạy k=1 của {config}")


def precision(tpr: float, fpr: float, rate: float) -> float:
    """Precision tại một tỷ lệ HIGH cho trước: TPR*rate / (TPR*rate + FPR*(1-rate)); NaN khi mẫu số bằng 0."""
    denominator = tpr * rate + fpr * (1 - rate)
    return tpr * rate / denominator if denominator else float("nan")


def fmt(value: float) -> str:
    """Định dạng một tỷ lệ (0 đến 1) thành phần trăm một chữ số thập phân, dùng dấu phẩy."""
    return f"{value * 100:.1f}%".replace(".", ",")


def table() -> str:
    """Dựng bảng Markdown precision của HIGH ở tỷ lệ 1%, 5% và 10% cho từng cấu hình C0 đến C5, kèm khoảng tin cậy Wilson của recall và báo sai."""
    gold = {r["id"]: r["level"] for r in read_jsonl(GOLD_PATH)}
    positives = [i for i, level in gold.items() if level == "HIGH"]
    negatives = [i for i, level in gold.items() if level != "HIGH"]
    lines = [f"Trong bộ t2, {len(positives)} trong {len(gold)} ca là HIGH ({fmt(len(positives) / len(gold))}); {len(negatives)} ca còn lại là LOW hoặc MEDIUM.", "",
             "| Cấu hình | Recall HIGH [Wilson 95%] | Báo sai HIGH [Wilson 95%] | Precision HIGH ở 1% | ở 5% | ở 10% |",
             "|:--|:--|:--|:--|:--|:--|"]
    for config in ("C0", "C1", "C2", "C3", "C4", "C5"):
        rows = latest_rows(config)
        tp = sum(rows[i]["level"] == "HIGH" for i in positives)
        fp = sum(rows[i]["level"] == "HIGH" for i in negatives)
        tpr, fpr = tp / len(positives), fp / len(negatives)
        (tl, th), (fl, fh) = wilson(tp, len(positives)), wilson(fp, len(negatives))
        cells = []
        for rate in RATES:
            low, high = precision(tl, fh, rate), precision(th, fl, rate)
            cells.append(f"{fmt(precision(tpr, fpr, rate))} [{fmt(low)}; {fmt(high)}]")
        lines.append(f"| {config} | {fmt(tpr)} [{fmt(tl)}; {fmt(th)}] | {fmt(fpr)} [{fmt(fl)}; {fmt(fh)}] | " + " | ".join(cells) + " |")
    return "\n".join(lines)


if __name__ == "__main__":
    print(table())
