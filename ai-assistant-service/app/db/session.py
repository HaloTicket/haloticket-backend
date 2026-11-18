"""Cấu hình kết nối cơ sở dữ liệu dùng chung: hàm tạo engine, session và lớp Base của SQLAlchemy."""
import os
import logging
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, declarative_base

log = logging.getLogger("ai.db")

CONNECT_TIMEOUT_SECONDS = 5


def make_engine(url, **options):
    """Một nơi duy nhất cấu hình pool cho mọi engine của dịch vụ.

    pool_pre_ping: kết nối bị cơ sở dữ liệu ngắt (khởi động lại, quá thời gian chờ) được thay trước khi dùng thay vì làm hỏng yêu cầu.
    pool_recycle: kết nối được làm mới trước khi máy chủ hoặc proxy tự đóng.
    connect_timeout: cơ sở dữ liệu không truy cập được sẽ báo lỗi sau 5 giây thay vì treo.
    """
    connect_args = dict(options.pop("connect_args", None) or {})
    connect_args.setdefault("connect_timeout", CONNECT_TIMEOUT_SECONDS)
    return create_engine(url, pool_pre_ping=True, pool_recycle=1800, connect_args=connect_args, **options)


# Đọc URL từ biến môi trường; không có thông tin đăng nhập mặc định. Thiếu URL thì engine là None và get_db trả
# AI_DB_NOT_CONFIGURED, còn bản thân dịch vụ vẫn khởi động.
DATABASE_URL = os.getenv("DATABASE_URL", "")

engine = None
SessionLocal = sessionmaker(autocommit=False, autoflush=False)
if DATABASE_URL:
    try:
        engine = make_engine(DATABASE_URL)
        SessionLocal.configure(bind=engine)
    except Exception as failure:  # URL sai hoặc thiếu driver: không để import bị sập
        log.error("Could not build the shared engine: %s", type(failure).__name__)
        engine = None
Base = declarative_base()


def get_db():
    """Dependency FastAPI cấp một session cho mỗi yêu cầu và đóng sau khi dùng; ném `AI_DB_NOT_CONFIGURED` nếu chưa có engine."""
    if engine is None:
        from app.core.errors import ai_error, AI_DB_NOT_CONFIGURED
        raise ai_error(AI_DB_NOT_CONFIGURED)
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
