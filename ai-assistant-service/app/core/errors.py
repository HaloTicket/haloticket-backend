"""Hợp đồng lỗi của các route AI: `AiError` được trả thành body phẳng `{code, message[, retry_after_seconds]}`.

Chỉ `AiError` được xử lý ở đây; `HTTPException` (detail dạng dict) giữ nguyên hình dạng cũ. Thông điệp luôn là văn bản cố định
thân thiện, không bao giờ là `str()` của một ngoại lệ khác.
"""
from typing import Optional

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

AI_INPUT_INVALID = "AI_INPUT_INVALID"
AI_RATE_LIMITED = "AI_RATE_LIMITED"
AI_BUDGET_EXHAUSTED = "AI_BUDGET_EXHAUSTED"
AI_UNAVAILABLE = "AI_UNAVAILABLE"
AI_DB_NOT_CONFIGURED = "AI_DB_NOT_CONFIGURED"
AI_DB_UNAVAILABLE = "AI_DB_UNAVAILABLE"
AI_GENERATION_FAILED = "AI_GENERATION_FAILED"
AI_QUESTION_REFUSED = "AI_QUESTION_REFUSED"

_TABLE = {
    AI_INPUT_INVALID: (422, "Dữ liệu gửi lên không hợp lệ."),
    AI_RATE_LIMITED: (429, "Bạn thao tác quá nhanh. Vui lòng thử lại sau ít phút."),
    AI_BUDGET_EXHAUSTED: (429, "Hệ thống AI đã hết lượt sử dụng trong ngày. Vui lòng thử lại vào ngày mai."),
    AI_UNAVAILABLE: (503, "Dịch vụ AI tạm thời không khả dụng. Vui lòng thử lại sau."),
    AI_DB_NOT_CONFIGURED: (503, "Dịch vụ AI chưa được cấu hình cơ sở dữ liệu."),
    AI_DB_UNAVAILABLE: (503, "Cơ sở dữ liệu của chức năng quản trị AI tạm thời không truy cập được."),
    AI_GENERATION_FAILED: (502, "AI không tạo được kết quả hợp lệ. Vui lòng thử lại."),
    AI_QUESTION_REFUSED: (422, "Câu hỏi này không được hỗ trợ."),
}


class AiError(Exception):
    """Lỗi nghiệp vụ của các route AI, mang mã lỗi, mã HTTP, thông điệp và (tùy chọn) số giây nên thử lại."""
    def __init__(self, code: str, status: int, message: str, retry_after: Optional[int] = None):
        """Khởi tạo lỗi với mã lỗi, mã HTTP, thông điệp hiển thị và số giây nên chờ trước khi thử lại (nếu có)."""
        super().__init__(code)
        self.code = code
        self.status = status
        self.message = message
        self.retry_after = retry_after


def ai_error(code: str, message: Optional[str] = None, retry_after: Optional[int] = None) -> AiError:
    """Tạo `AiError` từ mã lỗi, tra mã HTTP và thông điệp mặc định trong bảng; mã lạ thành 500."""
    status, default = _TABLE.get(code, (500, "Đã xảy ra lỗi."))
    return AiError(code, status, message or default, retry_after)


def install_error_handlers(app: FastAPI) -> None:
    """Đăng ký bộ xử lý biến `AiError` thành phản hồi JSON phẳng; lỗi 429 có thêm header `Retry-After`."""
    @app.exception_handler(AiError)
    async def _ai_error_handler(request: Request, exc: AiError):
        """Dựng phản hồi JSON cho `AiError`."""
        body = {"code": exc.code, "message": exc.message}
        headers = None
        if exc.retry_after is not None:
            body["retry_after_seconds"] = int(exc.retry_after)
            if exc.status == 429:
                headers = {"Retry-After": str(int(exc.retry_after))}
        return JSONResponse(status_code=exc.status, content=body, headers=headers)
