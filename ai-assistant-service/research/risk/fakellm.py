"""Mô hình giả có tính xác định, dùng cho kiểm thử và để chạy thử bộ chạy mà không tốn hạn mức.

Đây KHÔNG phải là phép đo nào cả: câu trả lời chỉ dựa vào vài danh sách từ khóa. Kết quả sinh ra từ nó không bao giờ được
báo cáo (bộ chạy đóng dấu llm="fake").
"""
import json
import re

SLANG = ("hàng nóng", "kẹo vui", "lá cỏ", "hàng trắng", "đồ chơi người lớn", "hàng cháy")
CONTACT = ("inb", "ib ", "kb ", "zalo", "za lô", "f.b")


def _find(text: str, words) -> str:
    """Tìm từ đầu tiên của words xuất hiện trong text (không phân biệt hoa thường); trả về đoạn gốc trong text hoặc chuỗi rỗng."""
    low = text.lower()
    for word in words:
        at = low.find(word)
        if at >= 0:
            return text[at:at + len(word)]
    return ""


def respond(stage: str, prompt: str, texts: dict) -> tuple:
    """Trả lời giả cho một bước gọi mô hình.

    Args:
        stage: bước của lời gọi, một trong flags, verify hoặc direct.
        prompt: nội dung prompt (bước verify đọc danh sách mã được hỏi từ đây).
        texts: các văn bản của sự kiện, dùng trường description.

    Returns:
        (văn bản JSON trả lời, tên mô hình giả).
    """
    description = texts.get("description", "")
    if stage == "flags":
        flags = []
        quote = _find(description, SLANG)
        if quote:
            flags.append({"code": "VEILED_PROHIBITED", "quote": quote, "confidence": "HIGH"})
        quote = _find(description, CONTACT)
        if quote and len(quote) >= 3:
            flags.append({"code": "SOCIAL_CONTACT", "quote": quote, "confidence": "MEDIUM"})
        return json.dumps({"flags": flags}, ensure_ascii=False), "fake-model"
    if stage == "verify":
        asked = re.findall(r"^- ([A-Z_]+) \(", prompt, flags=re.MULTILINE)
        return json.dumps({"confirmed": asked}), "fake-model"
    score = 80 if (_find(description, SLANG) or "vũ khí" in description.lower()) else 5
    return json.dumps({"risk_score": score}), "fake-model"
