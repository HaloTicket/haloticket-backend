"""Layer A: deterministic content rules (pure Python, no I/O at scoring time).

The rule set is a versioned JSON file. Matching runs on a normalised copy of each text (lower case, no Vietnamese marks,
one character per original character) so every match maps back to a position in the ORIGINAL text; the evidence carries
the original quote. A bad rule file does not stop the service: load_rules() reports it and the caller marks layer A as
unavailable instead of silently scoring "no findings".

A keyword is not a violation by itself. An event page that lists what visitors may NOT bring ("Nghiêm cấm thuốc lá, vũ khí...")
mentions the very same words as an advertisement for them. A match inside a prohibiting sentence, or inside a list introduced
by one, is therefore not scored; it is reported once as an informational finding (weight 0) so the reviewer sees why.
Contact details of the organiser (e-mail, phone, social links) are allowed and are not looked at.
"""
import json
import logging
import os
import re
import unicodedata
from dataclasses import dataclass, field
from typing import Optional

log = logging.getLogger("ai.risk.rules")

RULES_PATH = os.path.join(os.path.dirname(__file__), "risk_rules.v1.json")
SEVERITIES = ("MEDIUM", "HIGH", "BLOCK")
KINDS = ("phrases", "alcohol")
MAX_EVIDENCE_PER_RULE = 3
MAX_TEXT_CHARS = 20000
MAX_SCOPE_LOOKBACK = 30  # segments above a list item that are searched for the sentence that introduces the list

_ALCOHOL = re.compile(r"ruou[^.\n]{0,40}?(\d{2})\s*(?:do\b|%)")
_SEGMENT = re.compile(r"(?:[●•▪■◦]\s*)?[^\n●•▪■◦]+")
_SENTENCE_END = ".;!?"
_BULLET_START = re.compile(r"^\s*(?:[●•▪■◦·\-+*]|\d{1,2}[.)])\s*")


@dataclass(frozen=True)
class Rule:
    code: str
    kind: str
    severity: str
    weight: int
    message: str
    phrases: tuple = ()
    obfuscated: bool = False
    min_degrees: int = 15


@dataclass(frozen=True)
class RuleSet:
    version: str
    rules: tuple
    obfuscation: dict
    strong_ban: tuple = field(default_factory=tuple)
    lift: tuple = field(default_factory=tuple)


def load_rules(path: str = RULES_PATH) -> Optional[RuleSet]:
    """The validated rule set, or None when the file is missing or invalid (the caller reports RULES_UNAVAILABLE)."""
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        version = str(data["version"])
        rules = []
        seen = set()
        for raw in data["rules"]:
            code, kind, severity = raw["code"], raw["kind"], raw["severity"]
            if code in seen or kind not in KINDS or severity not in SEVERITIES:
                raise ValueError(f"bad rule {code}")
            seen.add(code)
            weight = int(raw["weight"])
            if not 0 < weight <= 100:
                raise ValueError(f"bad weight {code}")
            phrases = tuple(str(p) for p in raw.get("phrases", ()))
            if kind == "phrases" and not phrases:
                raise ValueError(f"no phrases {code}")
            rules.append(Rule(code, kind, severity, weight, str(raw["message"]), phrases,
                              bool(raw.get("obfuscated", False)), int(raw.get("min_degrees", 15))))
        context = data.get("context", {})
        strong = tuple(unicodedata.normalize("NFC", str(m)).lower() for m in context.get("strong_ban", ()))
        lift = tuple(unicodedata.normalize("NFC", str(m)).lower() for m in context.get("lift", ()))
        return RuleSet(version, tuple(rules), {str(k): str(v) for k, v in data.get("obfuscation_map", {}).items()}, strong, lift)
    except Exception as failure:
        log.error("Risk rule file rejected: %s", type(failure).__name__)
        return None


def normalise(text: str, obfuscation: Optional[dict] = None) -> tuple:
    """(normalised, index) with len(normalised) == len(index); index[i] is the position in `text` of normalised[i]."""
    out, index = [], []
    for position, char in enumerate(text):
        if char in ("đ", "Đ"):
            base = "d"
        else:
            decomposed = unicodedata.normalize("NFD", char)
            base = "".join(c for c in decomposed if not unicodedata.combining(c))
            if not base:
                continue  # a loose combining mark
            base = base[0]
        base = base.lower()
        if obfuscation and base in obfuscation:
            base = obfuscation[base]
        if not (base.isalnum() or base in "@%.:/+[]()-_$"):
            base = " "
        out.append(base)
        index.append(position)
    return "".join(out), index


def _spans_for_phrase(norm: str, phrase: str):
    start = 0
    needle = phrase.strip()
    while True:
        at = norm.find(needle, start)
        if at < 0:
            return
        end = at + len(needle)
        before_ok = at == 0 or not norm[at - 1].isalnum() or not needle[0].isalnum()
        after_ok = end == len(norm) or not norm[end].isalnum() or not needle[-1].isalnum()
        if before_ok and after_ok:
            yield at, end
        start = at + 1


def _evidence(field_name: str, original: str, index: list, start: int, end: int) -> dict:
    first, last = index[start], index[end - 1] + 1
    return {"field": field_name, "start": first, "end": last, "text": original[first:last][:160]}


def _scan(rule: Rule, field_name: str, original: str, norm: str, index: list, norm_obf: str) -> list:
    found = []
    if rule.kind == "alcohol":
        for m in _ALCOHOL.finditer(norm):
            if int(m.group(1)) >= rule.min_degrees:
                found.append(_evidence(field_name, original, index, m.start(), m.end()))
    else:
        spans = set()
        for phrase in rule.phrases:
            for span in _spans_for_phrase(norm, phrase):
                spans.add(span)
            if rule.obfuscated:
                for span in _spans_for_phrase(norm_obf, phrase):
                    spans.add(span)
        for start, end in sorted(spans):
            found.append(_evidence(field_name, original, index, start, end))
    return found


# context: is a match part of a sentence or list that forbids it?
def _segments(text: str) -> list:
    """[(start, end, bullet_like)] for every line and every bullet item of the text."""
    out = []
    for m in _SEGMENT.finditer(text):
        piece = m.group(0)
        out.append((m.start(), m.end(), bool(_BULLET_START.match(piece))))
    return out


def _clause_around(text: str, seg_start: int, seg_end: int, first: int, last: int) -> tuple:
    """(clause, match_start, match_end): the sentence of the segment that holds the match, and where the match sits in it."""
    left = seg_start
    for pos in range(first - 1, seg_start - 1, -1):
        if text[pos] in _SENTENCE_END:
            left = pos + 1
            break
    right = seg_end
    for pos in range(last, seg_end):
        if text[pos] in _SENTENCE_END:
            right = pos
            break
    return text[left:right], first - left, last - left


def _marker_outside(clause: str, markers: tuple, skip_from: int = -1, skip_to: int = -1) -> bool:
    """True when a marker occurs in the clause other than wholly inside the matched words (which are what is being judged).
    "không bảo đảm lợi nhuận" therefore counts as a negation although "bảo đảm" itself is the match."""
    low = clause.lower()
    for marker in markers:
        at = low.find(marker)
        while at >= 0:
            if not (at >= skip_from and at + len(marker) <= skip_to):
                return True
            at = low.find(marker, at + 1)
    return False


def is_prohibition_context(text: str, segments: list, first: int, last: int, ruleset: RuleSet) -> bool:
    """True when the match at [first, last) sits in a sentence that forbids or warns ("Nghiêm cấm...", "Không mang...",
    "ngăn chặn...") or in a list item whose introducing sentence or heading does. An advertisement such as "Bán vũ khí" stays a
    violation, and so does a sentence that lifts the ban ("không cấm", "được phép bán")."""
    position = next((i for i, (s, e, _b) in enumerate(segments) if s <= first < e), None)
    if position is None:
        return False
    seg_start, seg_end, bullet = segments[position]
    clause, m_start, m_end = _clause_around(text, seg_start, seg_end, first, last)
    if _marker_outside(clause, ruleset.lift):
        return False
    if _marker_outside(clause, ruleset.strong_ban, m_start, m_end):
        return True
    if not bullet:
        return False
    for upper in range(position - 1, max(-1, position - 1 - MAX_SCOPE_LOOKBACK), -1):  # a list item inherits the sentence above the list
        s, e, upper_bullet = segments[upper]
        piece = text[s:e]
        if not piece.strip() or upper_bullet:
            continue
        return _marker_outside(piece, ruleset.strong_ban) and not _marker_outside(piece, ruleset.lift)
    return False


def evaluate(texts: dict, ruleset: RuleSet) -> list:
    """Findings of layer A for {field: text}. Each rule fires at most once, with up to three distinct evidence items.
    Matches inside a prohibiting sentence or list are collected into one informational finding (weight 0)."""
    prepared = {}
    for field_name, raw in texts.items():
        original = (raw or "")[:MAX_TEXT_CHARS]
        norm, index = normalise(original)
        norm_obf, _ = normalise(original, ruleset.obfuscation)
        prepared[field_name] = (original, norm, index, norm_obf, _segments(original))
    findings, ignored = [], []
    for rule in ruleset.rules:
        evidence = []
        for field_name, (original, norm, index, norm_obf, segments) in prepared.items():
            if not norm:
                continue
            for item in _scan(rule, field_name, original, norm, index, norm_obf):
                if is_prohibition_context(original, segments, item["start"], item["end"], ruleset):
                    ignored.append(item)
                else:
                    evidence.append(item)
        unique, seen = [], set()
        for item in evidence:
            key = (item["field"], item["text"].lower())
            if key not in seen:
                seen.add(key)
                unique.append(item)
        if unique:
            findings.append({
                "layer": "RULE", "code": rule.code, "severity": rule.severity, "weight": rule.weight,
                "message": rule.message, "evidence": unique[:MAX_EVIDENCE_PER_RULE], "ruleset_version": ruleset.version,
            })
    if ignored:
        unique, seen = [], set()
        for item in ignored:
            key = (item["field"], item["text"].lower())
            if key not in seen:
                seen.add(key)
                unique.append(item)
        findings.append({
            "layer": "RULE", "code": "mentioned_in_prohibition", "severity": "INFO", "weight": 0,
            "message": "Có nhắc tới từ khóa nhạy cảm nhưng nằm trong điều khoản cấm hoặc cảnh báo nên không tính điểm.",
            "evidence": unique[:MAX_EVIDENCE_PER_RULE], "ruleset_version": ruleset.version,
        })
    return findings
