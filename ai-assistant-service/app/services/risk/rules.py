"""Layer A: deterministic content rules (pure Python, no I/O at scoring time).

The rule set is a versioned JSON file. Matching runs on a normalised copy of each text (lower case, no Vietnamese marks,
one character per original character) so every match maps back to a position in the ORIGINAL text; the evidence carries
the original quote. A bad rule file does not stop the service: load_rules() reports it and the caller marks layer A as
unavailable instead of silently scoring "no findings".
"""
import json
import logging
import os
import re
import unicodedata
from dataclasses import dataclass
from typing import Optional

log = logging.getLogger("ai.risk.rules")

RULES_PATH = os.path.join(os.path.dirname(__file__), "risk_rules.v1.json")
SEVERITIES = ("MEDIUM", "HIGH", "BLOCK")
KINDS = ("phone", "email", "phrases", "alcohol")
MAX_EVIDENCE_PER_RULE = 3
MAX_TEXT_CHARS = 20000

_PHONE = re.compile(r"(?<![\d])(?:(?:\+?\s?84|0)[\s.\-]*[3-9](?:[\s.\-]*\d){8}|0[\s.\-]*2\d(?:[\s.\-]*\d){8})(?![\d])")
_EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+\s?(?:@|\[at\]|\(at\))\s?[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")
_ALCOHOL = re.compile(r"ruou[^.\n]{0,40}?(\d{2})\s*(?:do\b|%)")


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
        return RuleSet(version, tuple(rules), {str(k): str(v) for k, v in data.get("obfuscation_map", {}).items()})
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


def _evidence(field: str, original: str, index: list, start: int, end: int) -> dict:
    first, last = index[start], index[end - 1] + 1
    return {"field": field, "start": first, "end": last, "text": original[first:last][:160]}


def _scan(rule: Rule, field: str, original: str, norm: str, index: list, norm_obf: str) -> list:
    found = []
    if rule.kind == "phone":
        for m in _PHONE.finditer(original):
            found.append({"field": field, "start": m.start(), "end": m.end(), "text": m.group(0).strip()[:160]})
    elif rule.kind == "email":
        for m in _EMAIL.finditer(original):
            found.append({"field": field, "start": m.start(), "end": m.end(), "text": m.group(0)[:160]})
    elif rule.kind == "alcohol":
        for m in _ALCOHOL.finditer(norm):
            if int(m.group(1)) >= rule.min_degrees:
                found.append(_evidence(field, original, index, m.start(), m.end()))
    else:
        spans = set()
        for phrase in rule.phrases:
            for span in _spans_for_phrase(norm, phrase):
                spans.add(span)
            if rule.obfuscated:
                for span in _spans_for_phrase(norm_obf, phrase):
                    spans.add(span)
        for start, end in sorted(spans):
            found.append(_evidence(field, original, index, start, end))
    return found


def evaluate(texts: dict, ruleset: RuleSet) -> list:
    """Findings of layer A for {field: text}. Each rule fires at most once, with up to three evidence items."""
    prepared = {}
    for field, raw in texts.items():
        original = (raw or "")[:MAX_TEXT_CHARS]
        norm, index = normalise(original)
        norm_obf, _ = normalise(original, ruleset.obfuscation)
        prepared[field] = (original, norm, index, norm_obf)
    findings = []
    for rule in ruleset.rules:
        evidence = []
        for field, (original, norm, index, norm_obf) in prepared.items():
            if not norm:
                continue
            evidence.extend(_scan(rule, field, original, norm, index, norm_obf))
        if evidence:
            findings.append({
                "layer": "RULE", "code": rule.code, "severity": rule.severity, "weight": rule.weight,
                "message": rule.message, "evidence": evidence[:MAX_EVIDENCE_PER_RULE],
                "ruleset_version": ruleset.version,
            })
    return findings
