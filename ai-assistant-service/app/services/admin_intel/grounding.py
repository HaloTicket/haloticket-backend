"""Kiểm tra mọi con số trong văn bản do mô hình viết đều có trong dữ liệu đã đưa cho mô hình.

Một số viết kiểu Việt (1.234.567 hoặc 12,5) hoặc kiểu Anh (1,234.5) được hiểu thành mọi giá trị có thể;
văn bản đạt khi ít nhất một cách hiểu của mỗi số nằm trong tập cho phép. Chữ số trong khóa JSON cũng được tính
(khóa như `transactions_7d` cho phép văn bản nói "7 ngày").
"""
import json
import re
from typing import Any, Iterable

_NUMBER = re.compile(r"\d[\d.,]*\d|\d")
_LIST_MARKER = re.compile(r"(?m)^\s*(?:[-*+]|\d+[.)])\s+")
_HEADING = re.compile(r"(?m)^\s*#{1,6}\s*")
_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}")  # chuỗi thời gian sẽ khiến mọi số ngày/tháng trông như có căn cứ


def _readings(token: str) -> set:
    """Các giá trị số có thể có của một token."""
    values = set()
    token = token.strip(".,")
    if not token:
        return values
    try:
        values.add(float(token.replace(",", "").replace(".", "")))  # dấu phân cách là phân nhóm hàng nghìn
    except ValueError:
        pass
    if "." in token and "," not in token:
        try:
            values.add(float(token))  # 12.5
        except ValueError:
            pass
    if "," in token and "." not in token:
        try:
            values.add(float(token.replace(",", ".")))  # 12,5
        except ValueError:
            pass
    if "," in token and "." in token:
        last = max(token.rfind(","), token.rfind("."))
        whole = re.sub(r"[.,]", "", token[:last])
        try:
            values.add(float(f"{whole}.{token[last + 1:]}"))
        except ValueError:
            pass
    return {round(v, 2) for v in values}


def numbers_in(text: str) -> list:
    """Trả `[(token, các cách hiểu)]` cho mọi số trong văn bản; số thứ tự danh sách và dấu thăng tiêu đề không phải là số."""
    cleaned = _HEADING.sub("", _LIST_MARKER.sub("", text or ""))
    return [(m.group(0), _readings(m.group(0))) for m in _NUMBER.finditer(cleaned)]


def _walk(value: Any, out: set) -> None:
    """Duyệt đệ quy dữ liệu kiểu JSON và thêm mọi số (kể cả số trong chuỗi và khóa) vào `out`; bỏ qua bool, None và chuỗi thời gian."""
    if isinstance(value, bool) or value is None:
        return
    if isinstance(value, (int, float)):
        out.add(round(float(value), 2))
    elif isinstance(value, str):
        if _TIMESTAMP.match(value):
            return
        for _token, readings in numbers_in(value):
            out |= readings
    elif isinstance(value, dict):
        for key, inner in value.items():
            for _token, readings in numbers_in(str(key)):
                out |= readings
            _walk(inner, out)
    elif isinstance(value, (list, tuple)):
        for inner in value:
            _walk(inner, out)


def allowed_numbers(*sources: Any) -> set:
    """Tập mọi số tìm thấy trong dữ liệu kiểu JSON hoặc chuỗi thường."""
    out: set = set()
    for source in sources:
        if isinstance(source, str):
            try:
                _walk(json.loads(source), out)
                continue
            except ValueError:
                pass
        _walk(source, out)
    return out


def unmatched(text: str, allowed: Iterable) -> list:
    """Các token trong `text` mà không cách hiểu nào nằm trong `allowed`."""
    allowed = set(allowed)
    return [token for token, readings in numbers_in(text) if readings and not (readings & allowed)]
