"""A deterministic stand-in for the model, for tests and for checking the runner without spending quota.

It is NOT a measurement of anything: its answers come from a few keyword lists. Results produced with it must never be
reported (the runner stamps them llm="fake")."""
import json
import re

SLANG = ("hàng nóng", "kẹo vui", "lá cỏ", "hàng trắng", "đồ chơi người lớn", "hàng cháy")
CONTACT = ("inb", "ib ", "kb ", "zalo", "za lô", "f.b")


def _find(text: str, words) -> str:
    low = text.lower()
    for word in words:
        at = low.find(word)
        if at >= 0:
            return text[at:at + len(word)]
    return ""


def respond(stage: str, prompt: str, texts: dict) -> tuple:
    """(text, model_name) for the stage of the call: flags, verify or direct."""
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
