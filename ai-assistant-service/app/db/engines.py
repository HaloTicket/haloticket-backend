"""Engine tạo lười và lưu đệm cho các role chỉ đọc hẹp quyền của chatbot (event_db / ticket_db)."""
import threading

from sqlalchemy.engine import Engine

from app.core.config import settings
from app.core.errors import ai_error, AI_DB_NOT_CONFIGURED
from app.db.session import make_engine

STATEMENT_TIMEOUT_MS = 8000

_engines: dict = {}
_lock = threading.Lock()


def _url_for(kind: str) -> str:
    """Chuỗi kết nối cấu hình cho loại `event` hoặc `ticket`; ném `ValueError` với loại khác."""
    if kind == "event":
        return settings.chatbot_event_database_url
    if kind == "ticket":
        return settings.chatbot_ticket_database_url
    raise ValueError("kind must be 'event' or 'ticket'")


def get_chatbot_engine(kind: str) -> Engine:
    """Engine chỉ đọc của chatbot theo loại; dùng lại engine đã tạo, tạo mới (và đóng engine cũ) khi URL đổi.

    Raises:
        AiError: `AI_DB_NOT_CONFIGURED` khi chưa cấu hình URL của loại đó.
    """
    url = _url_for(kind)
    if not url:
        raise ai_error(AI_DB_NOT_CONFIGURED)
    with _lock:
        cached = _engines.get(kind)
        if cached is not None and cached[0] == url:
            return cached[1]
        if cached is not None:
            cached[1].dispose()
        engine = make_engine(
            url,
            connect_args={"connect_timeout": 5, "options": f"-c statement_timeout={STATEMENT_TIMEOUT_MS}"},
        )
        _engines[kind] = (url, engine)
        return engine


def dispose_all() -> None:
    """Đóng mọi engine đã tạo và xóa bộ đệm (dùng khi tắt dịch vụ hoặc trong kiểm thử)."""
    with _lock:
        for _, engine in _engines.values():
            try:
                engine.dispose()
            except Exception:
                pass
        _engines.clear()
