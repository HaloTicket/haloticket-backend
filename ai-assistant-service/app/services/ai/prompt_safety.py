"""Untrusted text (user messages, history, event content, ticket names) goes into prompts only inside data blocks:

    <<<DATA-1a2b3c4d5e6f label
    ...text...
    DATA-1a2b3c4d5e6f>>>

The delimiter is random per request; any occurrence of it (and of the <<< / >>> markers) in the text is removed, and the
text is truncated, so the text cannot close its own block.
"""
import json
import re
import secrets

DEFAULT_MAX_CHARS = 600
_MARKER_RE = re.compile(r"<<<|>>>|DATA-[0-9A-Za-z]{6,}")
_BLOCK_OPEN_RE = re.compile(r"<{2,}\s*DATA[-_][0-9A-Za-z]{4,}(?:[ \t]+(?:tin nhắn của khách|lịch sử hội thoại|dữ liệu sự kiện từ cơ sở dữ liệu|dữ liệu sự kiện đang có|kết quả công cụ|tên sự kiện))?[ \t]*\n?")
_BLOCK_CLOSE_RE = re.compile(r"\n?[ \t]*DATA[-_][0-9A-Za-z]{4,}[ \t]*>{2,}")


class DataBlocks:
    def __init__(self):
        self.delimiter = "DATA-" + secrets.token_hex(6)

    def clean(self, value, max_chars: int = DEFAULT_MAX_CHARS) -> str:
        text = "" if value is None else str(value)
        text = text[:max_chars]
        text = text.replace(self.delimiter, "")
        text = _MARKER_RE.sub("", text)
        return text

    def wrap(self, value, label: str = "", max_chars: int = DEFAULT_MAX_CHARS) -> str:
        head = f"<<<{self.delimiter}" + (f" {label}" if label else "")
        return f"{head}\n{self.clean(value, max_chars)}\n{self.delimiter}>>>"

    def wrap_json_text(self, raw, label: str = "", max_field_chars: int = 300, max_total_chars: int = 6000) -> str:
        """A tool result (JSON text) for the model: every string value truncated and cleaned, then one block."""
        try:
            data = json.loads(raw) if isinstance(raw, str) else raw
        except (TypeError, ValueError):
            return self.wrap(raw, label, max_total_chars)
        cleaned = self._clean_json(data, max_field_chars)
        return self.wrap(json.dumps(cleaned, ensure_ascii=False), label, max_total_chars)

    def _clean_json(self, value, limit):
        if isinstance(value, str):
            return self.clean(value, limit)
        if isinstance(value, list):
            return [self._clean_json(v, limit) for v in value[:60]]
        if isinstance(value, dict):
            return {k: self._clean_json(v, limit) for k, v in value.items()}
        return value


def strip_markers(text) -> str:
    """Removes data-block markup a model echoed into its own answer (an opening line with its label, a closing delimiter,
    stray <<< / >>>). The answer must be plain wording for the customer, never the block format of the prompt."""
    value = "" if text is None else str(text)
    value = _BLOCK_OPEN_RE.sub("", value)
    value = _BLOCK_CLOSE_RE.sub("", value)
    value = re.sub(r"<{3,}|>{3,}", "", value)
    return value.strip()
