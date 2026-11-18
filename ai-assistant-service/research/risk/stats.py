"""Các thống kê mà nghiên cứu báo cáo, bằng Python thuần (không cần numpy hay scipy để chạy)."""
import math
import random
from typing import Callable, Sequence


def wilson(successes: int, n: int, z: float = 1.959964) -> tuple:
    """Khoảng tin cậy Wilson 95% cho một tỷ lệ. Trả về (0.0, 1.0) khi n bằng 0."""
    if n <= 0:
        return 0.0, 1.0
    p = successes / n
    denom = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom
    return max(0.0, centre - half), min(1.0, centre + half)


def prf(tp: int, fp: int, fn: int) -> tuple:
    """Tính precision, recall và F1 từ số dương thật, dương giả và âm giả; mẫu số bằng 0 thì giá trị là 0.0."""
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return precision, recall, f1


def confusion(gold: Sequence, pred: Sequence, labels: Sequence) -> dict:
    """Dựng ma trận nhầm lẫn {nhãn chuẩn: {nhãn dự đoán: số ca}} trên các nhãn cho trước."""
    matrix = {g: {p: 0 for p in labels} for g in labels}
    for g, p in zip(gold, pred):
        matrix[g][p] += 1
    return matrix


def macro_f1(gold: Sequence, pred: Sequence, labels: Sequence) -> float:
    """Macro F1: trung bình cộng F1 của từng nhãn."""
    scores = []
    for label in labels:
        tp = sum(1 for g, p in zip(gold, pred) if g == label and p == label)
        fp = sum(1 for g, p in zip(gold, pred) if g != label and p == label)
        fn = sum(1 for g, p in zip(gold, pred) if g == label and p != label)
        scores.append(prf(tp, fp, fn)[2])
    return sum(scores) / len(scores) if scores else 0.0


def cohen_kappa(a: Sequence, b: Sequence, labels: Sequence, weights: str = "none") -> float:
    """Kappa Cohen giữa hai người đánh giá. weights: none, linear hoặc quadratic (cho nhãn có thứ tự như LOW < MEDIUM < HIGH).

    Raises:
        ValueError: khi hai danh sách rỗng hoặc khác độ dài.
    """
    n = len(a)
    if n == 0 or n != len(b):
        raise ValueError("two non-empty lists of the same length are needed")
    index = {label: i for i, label in enumerate(labels)}
    k = len(labels)
    observed = [[0.0] * k for _ in range(k)]
    for x, y in zip(a, b):
        observed[index[x]][index[y]] += 1
    row = [sum(observed[i]) / n for i in range(k)]
    col = [sum(observed[i][j] for i in range(k)) / n for j in range(k)]

    def weight(i: int, j: int) -> float:
        """Trọng số bất đồng giữa nhãn thứ i và thứ j: 0 hoặc 1 khi không có trọng số, ngược lại là khoảng cách chuẩn hóa (bình phương nếu quadratic)."""
        if weights == "none":
            return 0.0 if i == j else 1.0
        distance = abs(i - j) / (k - 1) if k > 1 else 0.0
        return distance if weights == "linear" else distance ** 2

    observed_disagreement = sum(weight(i, j) * observed[i][j] / n for i in range(k) for j in range(k))
    expected_disagreement = sum(weight(i, j) * row[i] * col[j] for i in range(k) for j in range(k))
    if expected_disagreement == 0:
        return 1.0 if observed_disagreement == 0 else 0.0
    return 1 - observed_disagreement / expected_disagreement


def kappa_band(kappa: float) -> str:
    """Cách gọi mức đồng thuận theo Landis và Koch (1977), bằng tiếng Việt."""
    if kappa < 0:
        return "kém hơn ngẫu nhiên"
    for limit, name in ((0.20, "rất thấp"), (0.40, "thấp"), (0.60, "trung bình"), (0.80, "khá tốt")):
        if kappa <= limit:
            return name
    return "gần như hoàn toàn"


def bootstrap_ci(metric: Callable, rows: Sequence, iterations: int = 10000, seed: int = 7, alpha: float = 0.05) -> tuple:
    """Khoảng bootstrap phân vị của metric(các dòng lấy mẫu lại). Các dòng được lấy mẫu lại nguyên đơn vị (từng ca)."""
    rng = random.Random(seed)
    n = len(rows)
    if n == 0:
        return 0.0, 0.0
    values = sorted(metric([rows[rng.randrange(n)] for _ in range(n)]) for _ in range(iterations))
    return values[int(alpha / 2 * iterations)], values[min(iterations - 1, int((1 - alpha / 2) * iterations))]


def mcnemar(correct_a: Sequence, correct_b: Sequence) -> dict:
    """Kiểm định McNemar chính xác hai phía trên kết quả đúng hoặc sai theo cặp (nhị thức trên các cặp bất đồng).

    Returns:
        {"only_a", "only_b", "p"}: số ca chỉ A đúng, chỉ B đúng và giá trị p.
    """
    only_a = sum(1 for x, y in zip(correct_a, correct_b) if x and not y)
    only_b = sum(1 for x, y in zip(correct_a, correct_b) if y and not x)
    n = only_a + only_b
    if n == 0:
        return {"only_a": 0, "only_b": 0, "p": 1.0}
    k = min(only_a, only_b)
    tail = sum(math.comb(n, i) for i in range(0, k + 1)) / 2 ** n
    return {"only_a": only_a, "only_b": only_b, "p": min(1.0, 2 * tail)}


def holm(p_values: Sequence) -> list:
    """Giá trị p đã hiệu chỉnh theo Holm-Bonferroni, giữ nguyên thứ tự đầu vào."""
    m = len(p_values)
    order = sorted(range(m), key=lambda i: p_values[i])
    adjusted = [0.0] * m
    running = 0.0
    for rank, i in enumerate(order):
        running = max(running, (m - rank) * p_values[i])
        adjusted[i] = min(1.0, running)
    return adjusted


def mean_sd(values: Sequence) -> tuple:
    """Trung bình và độ lệch chuẩn mẫu (chia cho n - 1); danh sách rỗng cho (0.0, 0.0), một phần tử thì độ lệch là 0.0."""
    if not values:
        return 0.0, 0.0
    mean = sum(values) / len(values)
    if len(values) < 2:
        return mean, 0.0
    return mean, math.sqrt(sum((v - mean) ** 2 for v in values) / (len(values) - 1))


def fleiss_kappa(items: Sequence, labels: Sequence) -> float:
    """Kappa Fleiss cho nhiều người đánh giá. items: mỗi ca một danh sách nhãn (mọi ca có cùng số người đánh giá).

    Raises:
        ValueError: khi không có ca nào, ít hơn hai người đánh giá, hoặc số lượt đánh giá không đều.
    """
    n_items = len(items)
    if n_items == 0:
        raise ValueError("no items")
    raters = len(items[0])
    if raters < 2 or any(len(row) != raters for row in items):
        raise ValueError("every case needs the same number (at least two) of ratings")
    counts = [[row.count(label) for label in labels] for row in items]
    p_items = [(sum(c * c for c in row) - raters) / (raters * (raters - 1)) for row in counts]
    p_labels = [sum(row[j] for row in counts) / (n_items * raters) for j in range(len(labels))]
    observed, expected = sum(p_items) / n_items, sum(p * p for p in p_labels)
    return 1.0 if expected == 1 else (observed - expected) / (1 - expected)
