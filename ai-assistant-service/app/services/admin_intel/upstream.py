"""Các hàm đọc dữ liệu mà tính năng thông minh cho quản trị cần. Mọi thứ đi qua gateway với CHÍNH header Authorization của admin
(không thêm token, không thêm quyền cơ sở dữ liệu): các dịch vụ tự quyết định admin được đọc gì.

Mỗi hàm đọc ném ngoại lệ khi có lỗi; nơi gọi chuyển thành "chưa đánh giá" / null (không bao giờ thành giá trị bịa).
Các bộ đệm nhỏ trong tiến trình giúp các lần đọc nặng, không phân trang không bị lặp lại mỗi lần mở trang.
"""
import logging
import threading
import time
from typing import Any, Optional

import httpx
from sqlalchemy import text

from app.core.config import settings

log = logging.getLogger("ai.admin.upstream")

APPROVALS_TTL = 30
USER_TTL = 300
PRICES_TTL = 300

_lock = threading.Lock()
_cache: dict = {}


def reset_for_tests() -> None:
    """Xóa toàn bộ bộ đệm; chỉ dùng trong kiểm thử."""
    with _lock:
        _cache.clear()


def _cached(key: str, ttl: int, loader):
    """Trả giá trị trong bộ đệm nếu còn trong `ttl` giây, ngược lại gọi `loader` rồi lưu; bộ đệm quá 2000 mục thì xóa 500 mục cũ nhất."""
    now = time.time()
    with _lock:
        hit = _cache.get(key)
        if hit and now - hit[0] < ttl:
            return hit[1]
    value = loader()
    with _lock:
        _cache[key] = (time.time(), value)
        if len(_cache) > 2000:
            for stale in sorted(_cache, key=lambda k: _cache[k][0])[:500]:
                _cache.pop(stale, None)
    return value


cached = _cached  # tên công khai cho các module khác của admin_intel


def token_of(request) -> str:
    """Header Authorization nguyên bản của yêu cầu (đã được `require_admin` xác thực)."""
    return request.headers.get("Authorization", "")


def get_json(authorization: str, path: str, params: Optional[dict] = None) -> Any:
    """GET một đường dẫn của gateway nội bộ bằng token của admin và trả JSON; mã khác 200 thì ném `RuntimeError`."""
    url = settings.internal_api_base_url.rstrip("/") + path
    response = httpx.get(url, params=params, headers={"Authorization": authorization, "Accept": "application/json"},
                         timeout=settings.internal_api_timeout_seconds)
    if response.status_code != 200:
        raise RuntimeError(f"upstream {path} -> {response.status_code}")
    return response.json()


def approvals(authorization: str, fresh: bool = False) -> list:
    """Mọi sự kiện kèm trạng thái duyệt (dict dạng EventResponse). Endpoint này không phân trang. `fresh=True` bỏ bộ đệm."""
    key = "approvals"
    if fresh:
        with _lock:
            _cache.pop(key, None)

    def load():
        """Tải danh sách sự kiện và kiểm tra là một danh sách."""
        data = get_json(authorization, "/api/events/approvals", {"status": "ALL"})
        if not isinstance(data, list):
            raise RuntimeError("approvals is not a list")
        return [e for e in data if isinstance(e, dict)]

    return _cached(key, APPROVALS_TTL, load)


def user(authorization: str, user_id: str) -> dict:
    """Hồ sơ người dùng (do admin xem) theo mã, lưu đệm 5 phút."""
    data = _cached("user:" + user_id, USER_TTL, lambda: get_json(authorization, f"/api/users/admin/{user_id}"))
    if not isinstance(data, dict):
        raise RuntimeError("user is not an object")
    return data


def category_prices(category: str) -> list:
    """Giá vé thấp nhất (`min_price`) của các sự kiện APPROVED trong một danh mục, đọc bằng role phân tích từ `ai_analytics.v_events`."""
    from app.services.ai import analysis_service  # nạp lười: cần tài khoản phân tích đã được cấu hình

    def load():
        """Truy vấn chỉ đọc, có giới hạn thời gian, lấy tối đa 500 mức giá."""
        engine = analysis_service.engine_for("analysis")
        engine.require_account()
        with engine.engine.connect() as conn:
            try:
                conn.execute(text("SET TRANSACTION READ ONLY"))
                conn.execute(text("SET LOCAL statement_timeout = 3000"))
                conn.execute(text("SELECT set_config('app.allowed_event_ids', '*', true)"))
                rows = conn.execute(text("SELECT min_price FROM v_events WHERE approval_status = 'APPROVED' "
                                         "AND category_name = :c AND min_price IS NOT NULL LIMIT 500"), {"c": category}).fetchall()
            finally:
                conn.rollback()
        return [float(r[0]) for r in rows]

    return _cached("prices:" + category, PRICES_TTL, load)


def reconciliation_summary(authorization: str) -> dict:
    """Tổng quan đối soát thanh toán từ booking-service."""
    return get_json(authorization, "/api/bookings/admin/reconciliation/summary")


def reconciliation_exceptions(authorization: str) -> dict:
    """Tối đa 100 ngoại lệ đối soát đang mở (OPEN)."""
    return get_json(authorization, "/api/bookings/admin/reconciliation/exceptions", {"status": "OPEN", "page": 0, "size": 100})


def paid_without_tickets(authorization: str) -> dict:
    """Các đơn đã thanh toán nhưng chưa phát hành đủ vé."""
    return get_json(authorization, "/api/bookings/admin/reconciliation/paid-without-tickets")


def sales_timeline(authorization: str, date_from: str, date_to: str) -> dict:
    """Báo cáo doanh số của event-service trong khoảng ngày, dùng để lấy đường thời gian đơn hàng và doanh thu theo ngày."""
    # báo cáo chỉ nhận kích thước trang 10, 20 và 50 (giá trị khác trả 400); ở đây chỉ dùng phần dòng thời gian theo ngày
    return get_json(authorization, "/api/events/admin/reports", {"from": date_from, "to": date_to, "page": 1, "size": 10})


def resale_summary(authorization: str) -> dict:
    """Tổng quan tin bán lại vé đang chờ duyệt từ marketplace."""
    return get_json(authorization, "/api/marketplace/admin/listings/summary")
