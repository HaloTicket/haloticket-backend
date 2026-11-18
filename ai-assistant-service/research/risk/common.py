"""Hằng số và hàm đọc ghi tệp dùng chung của nghiên cứu chấm điểm rủi ro. Chạy mọi thứ từ thư mục ai-assistant-service/ bằng
python -m research.risk.<module>.
"""
import json
import os
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
# RISK_SET=<tên> đưa mọi công cụ sang một bộ ca riêng thứ hai (data/<tên>, labels/<tên>, results/<tên>); để trống là bộ đầu tiên
SET = os.environ.get("RISK_SET", "").strip()


def _in_set(folder: str) -> str:
    """Trả về đường dẫn thư mục con của bộ ca đang dùng: có thêm tên bộ khi RISK_SET được đặt."""
    return os.path.join(HERE, folder, SET) if SET else os.path.join(HERE, folder)


DATA = _in_set("data")
LABELS = _in_set("labels")
RESULTS = _in_set("results")
CACHE = os.path.join(HERE, "cache")
# RISK_CASES đưa các công cụ sang một tệp ca khác (người gán nhãn nhận cases_blind.jsonl: không có nhóm, không có nhãn dự định)
def _default_cases() -> str:
    """Chọn tệp ca mặc định: tệp đầy đủ nếu có, nếu chỉ có tệp ẩn nhãn thì dùng tệp đó."""
    full, blind = os.path.join(DATA, "cases.jsonl"), os.path.join(DATA, "cases_blind.jsonl")
    return full if os.path.exists(full) or not os.path.exists(blind) else blind  # bản checkout của người gán nhãn chỉ có tệp ẩn nhãn


CASES_PATH = os.environ.get("RISK_CASES", "").strip() or _default_cases()
SPLIT_PATH = os.path.join(DATA, "split.json")
GOLD_PATH = os.path.join(DATA, "gold.jsonl")

LEVELS = ("LOW", "MEDIUM", "HIGH")

# các mã mà người gán nhãn đánh dấu (hướng dẫn gán nhãn định nghĩa từng mã); thứ tự là thứ tự hiện trên màn hình
CODES = ("PROHIBITED_GOODS", "OFF_PLATFORM_CONTACT", "SCAM", "OVERPROMISE",
         "ORGANIZER_RISK", "PRICE_ANOMALY", "DUPLICATE", "SCHEDULE_ANOMALY")
CODE_KEYS = dict(zip("posvrxdt", CODES))  # p,o,s,v,r,x,d,t

# mã phát hiện của hệ thống -> mã của người gán nhãn tương ứng
SYSTEM_TO_HUMAN = {
    "prohibited_goods": "PROHIBITED_GOODS", "prohibited_ads": "PROHIBITED_GOODS", "alcohol_strength": "PROHIBITED_GOODS",
    "VEILED_PROHIBITED": "PROHIBITED_GOODS",
    "contact_social": "OFF_PLATFORM_CONTACT", "SOCIAL_CONTACT": "OFF_PLATFORM_CONTACT",
    "profit_guarantee": "SCAM", "SCAM_PATTERN": "SCAM",
    "overpromise": "OVERPROMISE", "OVERPROMISE": "OVERPROMISE",
    "organizer_account_new": "ORGANIZER_RISK", "organizer_account_young": "ORGANIZER_RISK",
    "organizer_ekyc_not_approved": "ORGANIZER_RISK", "organizer_history_rejected": "ORGANIZER_RISK",
    "price_outlier": "PRICE_ANOMALY", "near_duplicate": "DUPLICATE", "schedule_anomaly": "SCHEDULE_ANOMALY",
}

# thời điểm cố định để đánh giá mọi tín hiệu, giúp một lần chạy có thể lặp lại và so sánh
FIXED_NOW = datetime(2026, 11, 8, 12, 0, tzinfo=timezone.utc)


def read_jsonl(path: str) -> list:
    """Đọc tệp JSON Lines; trả về danh sách rỗng khi tệp chưa tồn tại."""
    if not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8-sig") as handle:  # utf-8-sig: tệp lưu kèm BOM vẫn đọc được
        return [json.loads(line) for line in handle if line.strip()]


def write_jsonl(path: str, rows: list) -> None:
    """Ghi đè tệp JSON Lines (UTF-8, xuống dòng LF) từ danh sách dòng, tự tạo thư mục cha."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=False) + "\n")


def append_jsonl(path: str, row: dict) -> None:
    """Thêm một dòng vào cuối tệp JSON Lines, tự tạo thư mục cha."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def load_cases(path: str = CASES_PATH) -> tuple:
    """Đọc tệp ca.

    Returns:
        (tiêu đề, danh sách ca). Tiêu đề chứa tập các sự kiện khác dùng chung; approvals của mỗi ca được ghép từ tập đó và approvals_extra.

    Raises:
        FileNotFoundError: khi tệp rỗng hoặc không tồn tại.
    """
    rows = read_jsonl(path)
    if not rows:
        raise FileNotFoundError(f"{path} is empty or missing: run python -m research.risk.generate")
    header, cases = rows[0], rows[1:]
    for case in cases:
        fixtures = case["fixtures"]
        fixtures["approvals"] = list(header.get("pool", [])) + list(fixtures.get("approvals_extra", []))
    return header, cases


def now_iso() -> str:
    """Thời điểm hiện tại theo UTC, dạng ISO 8601 chính xác đến giây."""
    return datetime.now(timezone.utc).isoformat(timespec="seconds")
