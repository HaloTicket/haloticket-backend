"""Lớp A: các luật nội dung tất định (Python thuần, không I/O ở thời điểm chấm điểm).

Bộ luật là một tệp JSON có phiên bản. Việc so khớp chạy trên bản chuẩn hóa của mỗi văn bản (chữ thường, bỏ dấu tiếng Việt,
mỗi ký tự gốc ứng với một ký tự) để mọi kết quả khớp đều quy về được vị trí trong văn bản GỐC; bằng chứng mang trích dẫn gốc.
Tệp luật hỏng không làm dừng service: load_rules() báo lỗi và nơi gọi đánh dấu lớp A là không khả dụng, thay vì âm thầm
chấm điểm "không có phát hiện".

Một từ khóa tự nó chưa phải vi phạm. Trang sự kiện liệt kê những thứ khách KHÔNG được mang theo ("Nghiêm cấm thuốc lá, vũ khí...")
nhắc đúng những từ mà một quảng cáo bán chúng cũng nhắc. Vì vậy, kết quả khớp nằm trong câu cấm, hoặc trong danh sách do một câu cấm
dẫn nhập, không được tính điểm; nó chỉ được báo một lần như phát hiện mang tính thông tin (trọng số 0) để người duyệt thấy lý do.
Thông tin liên hệ của ban tổ chức (e-mail, điện thoại, liên kết mạng xã hội) được phép và không bị xem xét.
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
MAX_SCOPE_LOOKBACK = 30  # số đoạn phía trên một mục danh sách được tìm để lấy câu dẫn nhập của danh sách

# ký tự không hiển thị gì, và chữ cái của bảng chữ khác trông giống chữ Latin, được dùng để lách danh sách từ khóa
_ZERO_WIDTH = frozenset("\u200b\u200c\u200d\u2060\ufeff\u00ad")
_LOOKALIKE = {"а": "a", "е": "e", "о": "o", "р": "p", "с": "c", "х": "x", "у": "y", "і": "i", "ѕ": "s", "ј": "j",
              "ο": "o", "α": "a", "ε": "e", "ι": "i", "ν": "v", "ρ": "p", "τ": "t"}
_SPACED_RUN = re.compile(r"(?<![a-z0-9])[a-z0-9](?:[ .\-_*]+[a-z0-9]){2,}(?![a-z0-9])")  # ví dụ "z a l o", "i.n.b.o.x"
_PHRASE_SEPARATORS = re.compile(r"[ .\-_*/:]")
MIN_COLLAPSED_PHRASE = 4  # ngắn hơn mức này thì dãy chữ tách rời có thể khớp nhầm
_ALCOHOL = re.compile(r"ruou[^.\n]{0,40}?(\d{2})\s*(?:do\b|%)")
_SEGMENT = re.compile(r"(?:[●•▪■◦]\s*)?[^\n●•▪■◦]+")
_SENTENCE_END = ".;!?"
_BULLET_START = re.compile(r"^\s*(?:[●•▪■◦·\-+*]|\d{1,2}[.)])\s*")


@dataclass(frozen=True)
class Rule:
    """Một luật nội dung: mã, loại (phrases hoặc alcohol), mức nghiêm trọng, trọng số, thông điệp và các cụm từ cần khớp."""
    code: str
    kind: str
    severity: str
    weight: int
    message: str
    phrases: tuple = ()
    obfuscated: bool = False
    min_degrees: int = 15
    min_level: str = "LOW"  # mức thấp nhất mà riêng phát hiện này có thể tạo ra (sàn chính sách)


@dataclass(frozen=True)
class RuleSet:
    """Bộ luật đã nạp: phiên bản, danh sách luật, bảng quy đổi ký tự che chữ, các dấu hiệu cấm mạnh (strong_ban) và dấu hiệu gỡ cấm (lift)."""
    version: str
    rules: tuple
    obfuscation: dict
    strong_ban: tuple = field(default_factory=tuple)
    lift: tuple = field(default_factory=tuple)


def _level(value, code: str) -> str:
    """Kiểm tra min_level hợp lệ (LOW, MEDIUM, HIGH); ném ValueError nếu không."""
    if value not in ("LOW", "MEDIUM", "HIGH"):
        raise ValueError(f"bad min_level {code}")
    return value


def load_rules(path: str = RULES_PATH) -> Optional[RuleSet]:
    """Bộ luật đã được kiểm tra hợp lệ, hoặc None khi tệp thiếu hoặc không hợp lệ (nơi gọi báo RULES_UNAVAILABLE)."""
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
                              bool(raw.get("obfuscated", False)), int(raw.get("min_degrees", 15)), _level(raw.get("min_level", "LOW"), code)))
        context = data.get("context", {})
        strong = tuple(unicodedata.normalize("NFC", str(m)).lower() for m in context.get("strong_ban", ()))
        lift = tuple(unicodedata.normalize("NFC", str(m)).lower() for m in context.get("lift", ()))
        return RuleSet(version, tuple(rules), {str(k): str(v) for k, v in data.get("obfuscation_map", {}).items()}, strong, lift)
    except Exception as failure:
        log.error("Risk rule file rejected: %s", type(failure).__name__)
        return None


def normalise(text: str, obfuscation: Optional[dict] = None) -> tuple:
    """Trả (normalised, index) với len(normalised) == len(index); index[i] là vị trí trong `text` của normalised[i]. Bỏ dấu, quy ký tự giống chữ Latin và ký tự toàn chiều rộng về dạng thường."""
    out, index = [], []
    for position, char in enumerate(text):
        if char in _ZERO_WIDTH:
            continue  # không hiển thị gì: "za<zero-width>lo" phải đọc là "zalo"
        if char in ("đ", "Đ"):
            base = "d"
        elif char.lower() in _LOOKALIKE:
            base = _LOOKALIKE[char.lower()]
        else:
            decomposed = unicodedata.normalize("NFKD", char)  # đồng thời đưa chữ toàn chiều rộng (ｆａｃｅ) về chữ thường
            base = "".join(c for c in decomposed if not unicodedata.combining(c))
            if not base:
                continue  # dấu kết hợp đứng lẻ
            base = base[0]
        base = base.lower()
        if obfuscation and base in obfuscation:
            base = obfuscation[base]
        if not (base.isalnum() or base in "@%.:/+[]()-_$"):
            base = " "
        out.append(base)
        index.append(position)
    return "".join(out), index


def collapse_spaced_runs(norm: str, index: list) -> tuple:
    """Trả (collapsed, index, runs). Các chữ cái viết rời từng ký tự có dấu ngăn ("z a l o", "i.n.b.o.x") được nối lại;
    runs liệt kê [start, end) của mọi dãy đã nối trong văn bản thu gọn. Chỉ các dãy đó thay đổi, phần còn lại giữ nguyên."""
    out, idx, runs, last = [], [], [], 0
    size = 0
    for m in _SPACED_RUN.finditer(norm):
        out.append(norm[last:m.start()])
        idx.extend(index[last:m.start()])
        size += m.start() - last
        begin = size
        for position in range(m.start(), m.end()):
            if norm[position].isalnum():
                out.append(norm[position])
                idx.append(index[position])
                size += 1
        runs.append((begin, size))
        last = m.end()
    out.append(norm[last:])
    idx.extend(index[last:])
    return "".join(out), idx, runs


def _spans_in_runs(collapsed: str, runs: list, phrase: str):
    """Các khoảng của cụm từ (viết không có dấu cách và dấu chấm) nằm hoàn toàn trong một dãy đã nối."""
    needle = _PHRASE_SEPARATORS.sub("", phrase)
    if len(needle) < MIN_COLLAPSED_PHRASE:
        return
    start = 0
    while True:
        at = collapsed.find(needle, start)
        if at < 0:
            return
        end = at + len(needle)
        if any(a <= at and end <= b for a, b in runs):
            yield at, end
        start = at + 1


def _spans_for_phrase(norm: str, phrase: str):
    """Các khoảng khớp của cụm từ trong văn bản chuẩn hóa, chỉ tính khi khớp trọn từ (hai đầu không dính ký tự chữ-số)."""
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
    """Dựng một bằng chứng {field, start, end, text} theo vị trí trong văn bản gốc; trích dẫn cắt tối đa 160 ký tự."""
    first, last = index[start], index[end - 1] + 1
    return {"field": field_name, "start": first, "end": last, "text": original[first:last][:160]}


def _scan(rule: Rule, field_name: str, original: str, norm: str, index: list, norm_obf: str, collapsed=None) -> list:
    """Quét một luật trên một trường: luật alcohol khớp độ cồn từ min_degrees trở lên, luật phrases khớp cụm từ (kể cả dạng che chữ và dạng viết rời). Trả danh sách bằng chứng."""
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
        if rule.obfuscated and collapsed is not None:
            joined, joined_index, runs = collapsed
            if runs:
                joined_spans = set()
                for phrase in rule.phrases:
                    joined_spans.update(_spans_in_runs(joined, runs, phrase))
                for start, end in sorted(joined_spans):
                    found.append(_evidence(field_name, original, joined_index, start, end))
    return found


# ngữ cảnh: kết quả khớp có nằm trong câu hoặc danh sách cấm nó không?
def _segments(text: str) -> list:
    """[(start, end, bullet_like)] cho mỗi dòng và mỗi mục gạch đầu dòng của văn bản."""
    out = []
    for m in _SEGMENT.finditer(text):
        piece = m.group(0)
        out.append((m.start(), m.end(), bool(_BULLET_START.match(piece))))
    return out


def _clause_around(text: str, seg_start: int, seg_end: int, first: int, last: int) -> tuple:
    """(clause, match_start, match_end): câu của đoạn chứa kết quả khớp, và vị trí của kết quả khớp trong câu đó."""
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
    """True khi có một dấu hiệu xuất hiện trong mệnh đề nhưng không nằm trọn trong chính những từ đã khớp (thứ đang được xét).
    Vì vậy "không bảo đảm lợi nhuận" vẫn tính là phủ định dù chính "bảo đảm" là từ khớp."""
    low = clause.lower()
    for marker in markers:
        at = low.find(marker)
        while at >= 0:
            if not (at >= skip_from and at + len(marker) <= skip_to):
                return True
            at = low.find(marker, at + 1)
    return False


def is_prohibition_context(text: str, segments: list, first: int, last: int, ruleset: RuleSet) -> bool:
    """True khi kết quả khớp tại [first, last) nằm trong câu cấm hoặc cảnh báo ("Nghiêm cấm...", "Không mang...",
    "ngăn chặn...") hoặc trong mục danh sách mà câu dẫn nhập hay tiêu đề của nó là câu cấm. Quảng cáo như "Bán vũ khí" vẫn
    là vi phạm, và câu gỡ lệnh cấm ("không cấm", "được phép bán") cũng vậy."""
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
    for upper in range(position - 1, max(-1, position - 1 - MAX_SCOPE_LOOKBACK), -1):  # mục danh sách thừa hưởng câu nằm phía trên danh sách
        s, e, upper_bullet = segments[upper]
        piece = text[s:e]
        if not piece.strip() or upper_bullet:
            continue
        return _marker_outside(piece, ruleset.strong_ban) and not _marker_outside(piece, ruleset.lift)
    return False


def evaluate(texts: dict, ruleset: RuleSet) -> list:
    """Các phát hiện của lớp A cho {field: text}. Mỗi luật kích hoạt tối đa một lần, kèm tối đa ba bằng chứng khác nhau.
    Các kết quả khớp nằm trong câu hoặc danh sách cấm được gom vào một phát hiện mang tính thông tin (trọng số 0)."""
    prepared = {}
    for field_name, raw in texts.items():
        original = (raw or "")[:MAX_TEXT_CHARS]
        norm, index = normalise(original)
        norm_obf, index_obf = normalise(original, ruleset.obfuscation)
        prepared[field_name] = (original, norm, index, norm_obf, _segments(original), collapse_spaced_runs(norm_obf, index_obf))
    findings, ignored = [], []
    for rule in ruleset.rules:
        evidence = []
        for field_name, (original, norm, index, norm_obf, segments, collapsed) in prepared.items():
            if not norm:
                continue
            for item in _scan(rule, field_name, original, norm, index, norm_obf, collapsed):
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
                "min_level": rule.min_level,
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
