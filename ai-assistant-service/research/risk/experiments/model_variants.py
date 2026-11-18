"""So sánh trên tập dev các cách giúp lớp mô hình tìm thêm những gì luật bỏ sót, mà không sửa mã production:
python -m research.risk.experiments.model_variants [base|prompt|big|prompt_big]   (chỉ dev; không bao giờ dùng tập test ở đây)

base        prompt đang dùng (risk-c-v2) trên tầng "fast" (Groq gpt-oss-20b); câu trả lời lấy từ cache có sẵn
prompt      cùng mô hình với hướng dẫn chung bổ sung trong prompt (không có danh sách cụm từ ví dụ vì sẽ làm lộ bộ sinh ca)
big         prompt đang dùng trên tầng "smart" (Groq gpt-oss-120b, temperature 0)
prompt_big  cả hai
Mỗi biến thể có cache phản hồi riêng (cache/variant-<tên>.jsonl).
"""
import dataclasses
import json
import sys

from app.services.ai import ai_call
from app.services.risk import semantic
from research.risk import common, run_configs as rc
from research.risk.stats import macro_f1

EXTRA = (
    "\nLưu ý thêm khi đọc:\n"
    "- Cam kết lợi nhuận, lãi suất, đầu tư sinh lời, kiếm tiền dễ dàng là SCAM_PATTERN, KHÔNG phải OVERPROMISE. OVERPROMISE chỉ dành "
    "cho lời hứa phóng đại về chất lượng hay kết quả của chính sự kiện.\n"
    "- Hiểu cả tiếng lóng và cách nói vòng về ma túy, vũ khí và các chất hoặc hàng bị cấm; câu mập mờ nhưng ngầm chào bán thì vẫn là "
    "VEILED_PROHIBITED.\n"
    "- Lời dẫn khách mua vé hoặc liên hệ qua một kênh chat không nêu tên cũng là SOCIAL_CONTACT.\n"
)


def install(variant: str):
    """Cài biến thể vào mã đang chạy mà không sửa mã production: thêm hướng dẫn phụ vào prompt (biến thể có 'prompt') và/hoặc nâng tầng mô hình lên smart (biến thể có 'big')."""
    if "prompt" in variant:
        original = semantic.build_prompt
        semantic.build_prompt = lambda *args, **kwargs: original(*args, **kwargs) + EXTRA
        semantic.PROMPT_VERSION = "risk-c-v3exp"
    if "big" in variant:
        ai_call.PROFILES["risk_scoring"] = dataclasses.replace(ai_call.PROFILES["risk_scoring"], tier="smart")


def main(variant: str) -> None:
    """Chạy C4 và C5 trên tập dev với biến thể đã chọn rồi in F1, độ chính xác, bỏ sót và báo sai HIGH, số cờ giữ lại và độ chính xác, độ phủ từng mã."""
    gold = {r["id"]: r for r in common.read_jsonl(common.GOLD_PATH)}
    dev = set(json.load(open(common.SPLIT_PATH, encoding="utf-8"))["dev"])
    _, cases = common.load_cases()
    cases = [c for c in cases if c["id"] in dev]
    install(variant)
    gateway = rc.Gateway("live", "groq-oss20b" if variant == "base" else f"variant-{variant}")
    for config in ("C4", "C5"):
        rows = [rc.run_case(config, case, gateway, 0, "full") for case in cases]
        ok = [r for r in rows if r["status"] == "ok"]
        g, p = [gold[r["id"]]["level"] for r in ok], [r["level"] for r in ok]
        high = [(a, b) for a, b in zip(g, p) if a == "HIGH"]
        rest = [(a, b) for a, b in zip(g, p) if a != "HIGH"]
        codes = {}
        for code in ("PROHIBITED_GOODS", "OFF_PLATFORM_CONTACT", "OVERPROMISE", "SCAM"):
            tp = sum(1 for r in ok if code in r["codes"] and code in gold[r["id"]]["codes"])
            pred = sum(1 for r in ok if code in r["codes"]); real = sum(1 for r in ok if code in gold[r["id"]]["codes"])
            codes[code] = f"{tp}/{pred}p {tp}/{real}r"
        print(f"{variant:11s} {config}: n={len(ok)} failed={len(rows) - len(ok)} F1={macro_f1(g, p, list(common.LEVELS)):.3f} "
              f"acc={sum(a == b for a, b in zip(g, p)) / len(g):.3f} missHIGH={sum(1 for a, b in high if b != 'HIGH')}/{len(high)} "
              f"falseHIGH={sum(1 for a, b in rest if b == 'HIGH')}/{len(rest)} flags={sum(r['flags_kept'] for r in ok)} {codes}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "base")
