"""Văn bản không tin cậy (tin nhắn người dùng, lịch sử, nội dung sự kiện, tên vé) chỉ được đưa vào prompt bên trong khối dữ liệu:

    <<<DATA-1a2b3c4d5e6f label
    ...text...
    DATA-1a2b3c4d5e6f>>>

Dấu phân cách được sinh ngẫu nhiên theo từng yêu cầu; mọi chỗ trùng với nó (và các dấu <<< / >>>) trong văn bản đều bị xóa,
văn bản còn bị cắt ngắn, nên không thể tự đóng khối của chính nó.
"""
import json
import re
import secrets

DEFAULT_MAX_CHARS = 600
_MARKER_RE = re.compile(r"<<<|>>>|DATA-[0-9A-Za-z]{6,}")
_BLOCK_OPEN_RE = re.compile(r"<{2,}\s*DATA[-_][0-9A-Za-z]{4,}(?:[ \t]+(?:tin nhắn của khách|lịch sử hội thoại|dữ liệu sự kiện từ cơ sở dữ liệu|dữ liệu sự kiện đang có|kết quả công cụ|tên sự kiện))?[ \t]*\n?")
_BLOCK_CLOSE_RE = re.compile(r"\n?[ \t]*DATA[-_][0-9A-Za-z]{4,}[ \t]*>{2,}")


class DataBlocks:
    """Một tập khối dữ liệu của một yêu cầu, dùng chung một dấu phân cách ngẫu nhiên."""
    def __init__(self):
        """Sinh dấu phân cách ngẫu nhiên cho yêu cầu này."""
        self.delimiter = "DATA-" + secrets.token_hex(6)

    def clean(self, value, max_chars: int = DEFAULT_MAX_CHARS) -> str:
        """Chuyển giá trị thành chuỗi, cắt tới max_chars và xóa dấu phân cách hoặc dấu <<< / >>> để không đóng khối sớm."""
        text = "" if value is None else str(value)
        text = text[:max_chars]
        text = text.replace(self.delimiter, "")
        text = _MARKER_RE.sub("", text)
        return text

    def wrap(self, value, label: str = "", max_chars: int = DEFAULT_MAX_CHARS) -> str:
        """Làm sạch giá trị rồi bọc vào một khối dữ liệu có nhãn tùy chọn."""
        head = f"<<<{self.delimiter}" + (f" {label}" if label else "")
        return f"{head}\n{self.clean(value, max_chars)}\n{self.delimiter}>>>"

    def wrap_json_text(self, raw, label: str = "", max_field_chars: int = 300, max_total_chars: int = 6000) -> str:
        """Bọc kết quả công cụ (văn bản JSON) cho mô hình: mọi giá trị chuỗi được cắt và làm sạch, rồi gói thành một khối. Không phải JSON thì bọc nguyên văn bản."""
        try:
            data = json.loads(raw) if isinstance(raw, str) else raw
        except (TypeError, ValueError):
            return self.wrap(raw, label, max_total_chars)
        cleaned = self._clean_json(data, max_field_chars)
        return self.wrap(json.dumps(cleaned, ensure_ascii=False), label, max_total_chars)

    def _clean_json(self, value, limit):
        """Đệ quy làm sạch mọi chuỗi trong cấu trúc JSON; danh sách chỉ giữ tối đa 60 phần tử đầu."""
        if isinstance(value, str):
            return self.clean(value, limit)
        if isinstance(value, list):
            return [self._clean_json(v, limit) for v in value[:60]]
        if isinstance(value, dict):
            return {k: self._clean_json(v, limit) for k, v in value.items()}
        return value


def strip_markers(text) -> str:
    """Xóa phần định dạng khối dữ liệu mà mô hình lặp lại trong câu trả lời (dòng mở kèm nhãn, dấu phân cách đóng,
    các dấu <<< / >>> thừa). Câu trả lời phải là lời văn thuần cho khách, không bao giờ là định dạng khối của prompt."""
    value = "" if text is None else str(text)
    value = _BLOCK_OPEN_RE.sub("", value)
    value = _BLOCK_CLOSE_RE.sub("", value)
    value = re.sub(r"<{3,}|>{3,}", "", value)
    return value.strip()
