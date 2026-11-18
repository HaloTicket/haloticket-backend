"""Lệnh gọi mô hình có giới hạn và có đo lường cho các route thuộc phạm vi (agent-chat vẫn dùng llm_factory, không bị đụng tới).

- tối đa 2 nhà cung cấp cho mỗi lệnh gọi, thử lần lượt (Groq, rồi OpenAI, rồi Gemini; lấy hai nhà đầu tiên đã cấu hình)
- hạn chót tổng cứng cho mỗi lệnh gọi (thread pool có giới hạn + `future.result(timeout)`); luồng chạy quá hạn
  không thể bị hủy, nên mỗi client cũng có timeout riêng thấp hơn hạn chót một chút
- `max_tokens` lấy từ profile; mô hình được tạo lười và lưu đệm (không làm gì lúc import)
- mọi lần thử đều được đo qua `usage.record` (không bao giờ ném lỗi) và giữ trong vòng đệm bộ nhớ cho `provider_status()`
"""
import logging
import threading
import time
from collections import deque
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Optional

from langchain_google_genai import ChatGoogleGenerativeAI
from langchain_groq import ChatGroq
from langchain_openai import ChatOpenAI

from app.core.config import settings
from app.core.errors import ai_error, AI_UNAVAILABLE, AI_GENERATION_FAILED
from app.core import ratelimit
from app.services.ai import usage

log = logging.getLogger("ai.call")

_PLACEHOLDER_KEY = "your-default-key-if-missing"  # giá trị mặc định của settings.gemini_api_key, không phải khóa thật
PROVIDERS = ("groq", "openai", "gemini")
MAX_PROVIDERS_PER_CALL = 2
_RING_WINDOW_SECONDS = 24 * 3600

# các profile bị tính vào ngân sách ngày chung (chat hướng tới khách hàng)
BUDGETED_PROFILES = frozenset({"customer_chat"})


@dataclass(frozen=True)
class AiProfile:
    """Hồ sơ một loại lệnh gọi mô hình: tên, tầng mô hình (`fast` hoặc `smart`), số token tối đa và hạn chót tính bằng giây."""
    name: str
    tier: str  # một trong 'fast' | 'smart' (tầng mô hình)
    max_tokens: int
    deadline_s: int


def _build_profiles() -> dict:
    """Dựng các profile gọi mô hình từ cấu hình giới hạn token và hạn chót."""
    d = settings.ai_llm_deadline_seconds
    return {
        "customer_chat": AiProfile("customer_chat", "fast", settings.ai_max_tokens_customer_chat, d),
        "admin_assistant": AiProfile("admin_assistant", "fast", settings.ai_max_tokens_admin_assistant, d),
        "risk_scoring": AiProfile("risk_scoring", "fast", settings.ai_max_tokens_risk_scoring, d),
        "event_content": AiProfile("event_content", "smart", settings.ai_max_tokens_event_content, d),
        "analysis": AiProfile("analysis", "fast", settings.ai_max_tokens_analysis, d),
        "voucher_strategy": AiProfile("voucher_strategy", "fast", settings.ai_max_tokens_voucher_strategy, d),
        "dashboard_summary": AiProfile("dashboard_summary", "fast", settings.ai_max_tokens_dashboard_summary, d),
        "ai_probe": AiProfile("ai_probe", "fast", 1, d),
    }


PROFILES: dict = _build_profiles()

# Phần hạ tầng nội bộ
_executor = None
_executor_lock = threading.Lock()
_models: dict = {}
_models_lock = threading.Lock()
_ring: dict = {name: deque(maxlen=500) for name in PROVIDERS}  # mỗi phần tử là (thời điểm, thành công, mã lỗi)
_ring_lock = threading.Lock()


def _get_executor() -> ThreadPoolExecutor:
    """Thread pool dùng chung (tối đa 8 luồng), tạo lười ở lần dùng đầu."""
    global _executor
    with _executor_lock:
        if _executor is None:
            _executor = ThreadPoolExecutor(max_workers=8, thread_name_prefix="ai-call")
        return _executor


def _split_keys(value: str) -> list:
    """Tách chuỗi nhiều khóa API ngăn cách bằng dấu phẩy thành danh sách, bỏ khoảng trắng và mục rỗng."""
    return [k.strip() for k in (value or "").split(",") if k.strip()]


def _candidates(tier: str) -> list:
    """Danh sách có thứ tự `(nhà cung cấp, mô hình, nhiệt độ, khóa)` cho tầng; chỉ dùng `MAX_PROVIDERS_PER_CALL` mục đầu."""
    smart = tier == "smart"
    temp = 0.0 if smart else 0.2
    out = []
    groq = _split_keys(settings.groq_api_key)
    if groq:
        out.append(("groq", "openai/gpt-oss-120b" if smart else "openai/gpt-oss-20b", temp, groq[0]))
    if settings.openai_api_key:
        out.append(("openai", "gpt-4o-mini", temp, settings.openai_api_key))
    gemini = [k for k in _split_keys(settings.gemini_api_key) if k != _PLACEHOLDER_KEY]
    if gemini:
        out.append(("gemini", "gemini-3.5-flash", temp, gemini[0]))
    return _healthy_first(out)[:MAX_PROVIDERS_PER_CALL]


RECENT_FAILURE_SECONDS = 120


def _healthy_first(candidates: list) -> list:
    """Nhà cung cấp có hai kết quả gần nhất đều thất bại trong hai phút qua (hết quota, bị giới hạn, ngừng hoạt động) bị đẩy xuống cuối,
    để thử nhà kế tiếp thay vì gọi lại. Thứ tự còn lại giữ nguyên; không có nhà nào bị loại bỏ."""
    now = time.time()

    def failing(provider: str) -> bool:
        """Cho biết nhà cung cấp có hai lần thử gần nhất cùng thất bại trong `RECENT_FAILURE_SECONDS` giây hay không."""
        with _ring_lock:
            last = list(_ring[provider])[-2:]
        return len(last) == 2 and all(not ok and now - ts < RECENT_FAILURE_SECONDS for ts, ok, _code in last)

    return sorted(candidates, key=lambda c: failing(c[0]))  # sorted ổn định: False (khỏe) xếp trước


def _model_for(provider: str, model: str, temp: float, key: str, max_tokens: int, timeout: float):
    """Mô hình chat của nhà cung cấp, tạo lười và lưu đệm theo bộ tham số."""
    cache_key = (provider, model, temp, hash(key), max_tokens, timeout)
    with _models_lock:
        cached = _models.get(cache_key)
        if cached is not None:
            return cached
        if provider == "groq":
            extra = {"reasoning_effort": "low"} if "gpt-oss" in model else {}
            # mô hình suy luận tiêu token vào phần suy nghĩ trước: ở mức mặc định, câu trả lời ngắn có thể bị rỗng
            built = ChatGroq(api_key=key, model=model, temperature=temp, timeout=timeout, max_retries=1, max_tokens=max_tokens, **extra)
        elif provider == "openai":
            built = ChatOpenAI(api_key=key, model=model, temperature=temp, timeout=timeout, max_retries=1, max_tokens=max_tokens)
        else:
            built = ChatGoogleGenerativeAI(api_key=key, model=model, temperature=temp, timeout=timeout, max_retries=1,
                                           max_output_tokens=max_tokens)
        _models[cache_key] = built
        return built


def _content_text(message) -> str:
    """Văn bản của một phản hồi, ghép các phần nếu nội dung là danh sách; chuỗi rỗng nếu không có."""
    content = getattr(message, "content", message)
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for part in content:
            if isinstance(part, str):
                parts.append(part)
            elif isinstance(part, dict) and isinstance(part.get("text"), str):
                parts.append(part["text"])
        return "".join(parts)
    return "" if content is None else str(content)


def _is_empty(message) -> bool:
    """Phản hồi được coi là rỗng khi không có lệnh gọi công cụ và văn bản chỉ gồm khoảng trắng."""
    if getattr(message, "tool_calls", None):
        return False
    return not _content_text(message).strip()


def _chars_of(messages) -> int:
    """Tổng số ký tự của các tin nhắn đầu vào, dùng để ước lượng token khi nhà cung cấp không báo."""
    if isinstance(messages, str):
        return len(messages)
    total = 0
    try:
        for m in messages:
            total += len(_content_text(m)) if not isinstance(m, tuple) else len(str(m[1]))
    except Exception:
        pass
    return total


def _tokens(response, messages):
    """Trả `(token vào, token ra, ước lượng)`; dùng số liệu của nhà cung cấp nếu có, nếu không ước lượng bằng số ký tự chia 4."""
    meta = getattr(response, "usage_metadata", None)
    if isinstance(meta, dict) and (meta.get("input_tokens") is not None or meta.get("output_tokens") is not None):
        return meta.get("input_tokens"), meta.get("output_tokens"), False
    return max(1, _chars_of(messages) // 4), max(0, len(_content_text(response)) // 4), True


def _note(provider: str, ok: bool, error_code: Optional[str]) -> None:
    """Ghi kết quả một lần thử vào vòng đệm của nhà cung cấp."""
    with _ring_lock:
        _ring[provider].append((time.time(), ok, error_code))


def _code_of(failure: BaseException) -> str:
    """Mã lỗi ngắn của một lần thử thất bại: `TIMEOUT` hoặc tên kiểu ngoại lệ (không lộ nội dung lỗi)."""
    if isinstance(failure, FutureTimeout):
        return "TIMEOUT"
    return type(failure).__name__[:60]


# API công khai
def ai_invoke(profile: str, messages, *, actor: Optional[str] = None, tools: Optional[list] = None,
              tier: Optional[str] = None):
    """Một lệnh gọi mô hình có giới hạn. Trả về AIMessage của LangChain; ném AiError (AI_UNAVAILABLE / AI_GENERATION_FAILED /
    AI_BUDGET_EXHAUSTED). `messages` là bất cứ thứ gì mô hình chat nhận (danh sách tin nhắn hoặc chuỗi).
    Thử lần lượt các nhà cung cấp trong hạn chót của profile; mỗi lần thử đều được ghi nhận."""
    prof = PROFILES.get(profile)
    if prof is None:
        raise ValueError(f"unknown AI profile: {profile}")
    use_tier = tier or prof.tier
    if profile in BUDGETED_PROFILES:
        ratelimit.consume_budget()

    candidates = _candidates(use_tier)
    if not candidates:
        raise ai_error(AI_UNAVAILABLE)

    started = time.monotonic()
    deadline = started + prof.deadline_s
    client_timeout = max(1.0, prof.deadline_s * 0.9)
    last_empty = False
    attempts = 0
    for index, (provider, model_name, temp, key) in enumerate(candidates):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        attempts += 1
        share = remaining / (len(candidates) - index)  # nhà cung cấp thứ hai vẫn giữ phần thời gian của mình
        t0 = time.monotonic()
        error_code = None
        response = None
        try:
            model = _model_for(provider, model_name, temp, key, prof.max_tokens, client_timeout)
            if tools:
                model = model.bind_tools(tools)
            future = _get_executor().submit(model.invoke, messages)
            response = future.result(timeout=share)
            if _is_empty(response):
                error_code = "EMPTY_RESPONSE"
        except BaseException as failure:  # gồm cả timeout của future; không bao giờ lộ nguyên trạng ra cho nơi gọi
            if isinstance(failure, (KeyboardInterrupt, SystemExit)):
                raise
            error_code = _code_of(failure)
            log.warning("AI attempt failed (profile=%s provider=%s): %s", profile, provider, error_code)
        latency_ms = int((time.monotonic() - t0) * 1000)
        ok = error_code is None
        if response is not None:
            in_tok, out_tok, estimated = _tokens(response, messages)
        else:
            in_tok, out_tok, estimated = max(1, _chars_of(messages) // 4), 0, True
        _note(provider, ok, error_code)
        usage.record(route=profile, tier=use_tier, provider=provider, model=model_name, ok=ok, error_code=error_code,
                     latency_ms=latency_ms, input_tokens=in_tok, output_tokens=out_tok, estimated=estimated,
                     actor=actor, attempts=attempts)
        if ok:
            return response
        last_empty = error_code == "EMPTY_RESPONSE"
    raise ai_error(AI_GENERATION_FAILED if last_empty else AI_UNAVAILABLE)


class _MeteredLlm:
    """Bao ngoài `ai_invoke` thành đối tượng có `.invoke(...)` để tiêm vào SQLEngine."""
    def __init__(self, profile: str, actor: Optional[str]):
        """Gắn profile và định danh người gọi cho các lệnh gọi sau này."""
        self.profile = profile
        self.actor = actor

    def invoke(self, messages, **kwargs):
        """Gọi mô hình qua `ai_invoke`; chỉ chuyển tiếp tham số `tools` nếu có."""
        return ai_invoke(self.profile, messages, actor=self.actor, tools=kwargs.get("tools"))


def metered_llm(profile: str, actor: Optional[str] = None) -> _MeteredLlm:
    """Đối tượng có `.invoke(messages, **kwargs)` đi qua `ai_invoke` (để tiêm vào SQLEngine)."""
    return _MeteredLlm(profile, actor)


def provider_status() -> list:
    """Trạng thái thụ động của từng nhà cung cấp, suy từ kết quả trong bộ nhớ của 24 giờ qua. Không gọi mạng, kết quả không chứa khóa."""
    configured = {
        "groq": bool(_split_keys(settings.groq_api_key)),
        "openai": bool(settings.openai_api_key),
        "gemini": bool([k for k in _split_keys(settings.gemini_api_key) if k != _PLACEHOLDER_KEY]),
    }
    cutoff = time.time() - _RING_WINDOW_SECONDS
    result = []
    for name in PROVIDERS:
        with _ring_lock:
            events = [e for e in _ring[name] if e[0] >= cutoff]
        ok_events = [e for e in events if e[1]]
        fail_events = [e for e in events if not e[1]]
        if not events:
            status = "unknown"
        else:
            recent = [e[1] for e in events[-5:]]
            status = "ok" if all(recent) else ("down" if not any(recent) else "degraded")
        result.append({
            "name": name,
            "configured": configured[name],
            "status": status,
            "last_ok_at": datetime.fromtimestamp(ok_events[-1][0], timezone.utc).isoformat() if ok_events else None,
            "last_error_code": fail_events[-1][2] if fail_events else None,
            "ok_count": len(ok_events),
            "fail_count": len(fail_events),
        })
    return result


def reset_for_tests() -> None:
    """Xóa mô hình đã lưu đệm, vòng đệm kết quả và đóng thread pool; chỉ dùng trong kiểm thử."""
    global _executor
    with _models_lock:
        _models.clear()
    with _ring_lock:
        for d in _ring.values():
            d.clear()
    with _executor_lock:
        if _executor is not None:
            _executor.shutdown(wait=False)
            _executor = None
