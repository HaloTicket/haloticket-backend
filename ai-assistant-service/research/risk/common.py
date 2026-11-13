"""Shared constants and file helpers of the risk-scoring study. Run everything from ai-assistant-service/ with
python -m research.risk.<module>."""
import json
import os
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
# RISK_SET=<name> points every tool at a second, separate case set (data/<name>, labels/<name>, results/<name>); empty = the first set
SET = os.environ.get("RISK_SET", "").strip()


def _in_set(folder: str) -> str:
    return os.path.join(HERE, folder, SET) if SET else os.path.join(HERE, folder)


DATA = _in_set("data")
LABELS = _in_set("labels")
RESULTS = _in_set("results")
CACHE = os.path.join(HERE, "cache")
# RISK_CASES points the tools at another cases file (the annotators get cases_blind.jsonl: no group, no intended label)
def _default_cases() -> str:
    full, blind = os.path.join(DATA, "cases.jsonl"), os.path.join(DATA, "cases_blind.jsonl")
    return full if os.path.exists(full) or not os.path.exists(blind) else blind  # an annotator's checkout only has the blind file


CASES_PATH = os.environ.get("RISK_CASES", "").strip() or _default_cases()
SPLIT_PATH = os.path.join(DATA, "split.json")
GOLD_PATH = os.path.join(DATA, "gold.jsonl")

LEVELS = ("LOW", "MEDIUM", "HIGH")

# what a person marks (the labelling guide defines each); order is the order shown on screen
CODES = ("PROHIBITED_GOODS", "OFF_PLATFORM_CONTACT", "SCAM", "OVERPROMISE",
         "ORGANIZER_RISK", "PRICE_ANOMALY", "DUPLICATE", "SCHEDULE_ANOMALY")
CODE_KEYS = dict(zip("posvrxdt", CODES))  # p,o,s,v,r,x,d,t

# system finding code -> the human code it corresponds to
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

# the moment every signal is evaluated at, so a run can be repeated and compared
FIXED_NOW = datetime(2026, 11, 8, 12, 0, tzinfo=timezone.utc)


def read_jsonl(path: str) -> list:
    if not os.path.exists(path):
        return []
    with open(path, "r", encoding="utf-8-sig") as handle:  # utf-8-sig: a file saved with a BOM still reads
        return [json.loads(line) for line in handle if line.strip()]


def write_jsonl(path: str, rows: list) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=False) + "\n")


def append_jsonl(path: str, row: dict) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "a", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def load_cases(path: str = CASES_PATH) -> tuple:
    """(header, cases). The header carries the shared pool of other events every case's approvals start from."""
    rows = read_jsonl(path)
    if not rows:
        raise FileNotFoundError(f"{path} is empty or missing: run python -m research.risk.generate")
    header, cases = rows[0], rows[1:]
    for case in cases:
        fixtures = case["fixtures"]
        fixtures["approvals"] = list(header.get("pool", [])) + list(fixtures.get("approvals_extra", []))
    return header, cases


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")
