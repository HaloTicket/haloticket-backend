"""Checks that every number in a model-written text appears in the data the model was given.

A number written the Vietnamese way (1.234.567 or 12,5) or the English way (1,234.5) is read as every plausible value;
the text passes when at least one reading of each number is in the allowed set. Digits inside JSON keys count too
(a key like "transactions_7d" lets the text say "7 ngày").
"""
import json
import re
from typing import Any, Iterable

_NUMBER = re.compile(r"\d[\d.,]*\d|\d")
_LIST_MARKER = re.compile(r"(?m)^\s*(?:[-*+]|\d+[.)])\s+")
_HEADING = re.compile(r"(?m)^\s*#{1,6}\s*")
_TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}")  # a timestamp would make every day/month number look supported


def _readings(token: str) -> set:
    """Plausible numeric values of one token."""
    values = set()
    token = token.strip(".,")
    if not token:
        return values
    try:
        values.add(float(token.replace(",", "").replace(".", "")))  # separators as thousands
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
    """[(token, readings)] for every number in the text; list markers and heading hashes are not numbers."""
    cleaned = _HEADING.sub("", _LIST_MARKER.sub("", text or ""))
    return [(m.group(0), _readings(m.group(0))) for m in _NUMBER.finditer(cleaned)]


def _walk(value: Any, out: set) -> None:
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
    """Every number found in JSON-like data or plain strings."""
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
    """Tokens of `text` none of whose readings is in `allowed`."""
    allowed = set(allowed)
    return [token for token, readings in numbers_in(text) if readings and not (readings & allowed)]
