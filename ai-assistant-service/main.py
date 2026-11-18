"""Điểm vào của ai-assistant-service (FastAPI): khởi tạo cơ sở dữ liệu, đăng ký Eureka, gắn các router customer, organizer và admin."""
import asyncio
import logging

import py_eureka_client.eureka_client as eureka_client
from fastapi import FastAPI
from contextlib import asynccontextmanager
from sqlalchemy import text
from app.core.config import settings
from app.core.errors import install_error_handlers
from app.db import session as db_session
from app.models.event_vector import EventVector
from app.api.endpoints import customer
from app.api.endpoints import organizer
from app.api.endpoints import admin

log = logging.getLogger("ai.main")


def _init_database():
    """Bật extension pgvector và tạo bảng. Cố gắng hết sức và có giới hạn thời gian (engine có connect timeout 5 giây); không bao giờ làm dừng service."""
    engine = db_session.engine
    if engine is None:
        log.warning("DATABASE_URL is not set: skipping database initialisation")
        return
    try:
        with engine.connect() as conn:
            conn.execute(text("CREATE EXTENSION IF NOT EXISTS vector;"))
            conn.commit()
    except Exception as e:
        log.warning("Could not enable vector extension: %s", type(e).__name__)
    try:
        db_session.Base.metadata.create_all(bind=engine)
    except Exception as e:
        log.warning("Could not create tables: %s", type(e).__name__)


async def _register_with_eureka():
    """Chạy nền để Eureka chậm hoặc vắng mặt không làm trễ việc phục vụ. Thử đăng ký tối đa 10 lần, cách nhau 3 giây."""
    for attempt in range(1, 11):
        try:
            await eureka_client.init_async(
                eureka_server=settings.eureka_server,
                app_name=settings.app_name,
                instance_port=settings.port,
            )
            log.info("Eureka registration successful on attempt %s", attempt)
            return
        except asyncio.CancelledError:
            raise
        except Exception as e:
            log.warning("Eureka registration attempt %s/10 failed: %s", attempt, type(e).__name__)
            await asyncio.sleep(3)
    log.warning("Could not register with Eureka after 10 attempts. Service runs in standalone mode.")


async def _startup_tasks():
    """Tác vụ khởi động chạy nền: khởi tạo cơ sở dữ liệu (trong luồng riêng) rồi đăng ký Eureka; lỗi chỉ ghi log."""
    try:
        await asyncio.to_thread(_init_database)
    except Exception as e:
        log.warning("Database initialisation failed: %s", type(e).__name__)
    await _register_with_eureka()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Vòng đời ứng dụng: khi khởi động chạy tác vụ nền; khi tắt thì hủy tác vụ đó và gỡ đăng ký Eureka."""
    task = asyncio.create_task(_startup_tasks())
    yield
    task.cancel()
    try:
        await task
    except BaseException:
        pass
    try:
        await eureka_client.stop_async()
    except Exception as e:
        log.warning("Error stopping Eureka client: %s", type(e).__name__)

app = FastAPI(title="AI Assistant Service", lifespan=lifespan)
install_error_handlers(app)

@app.get("/health")
async def health_check():
    """Endpoint kiểm tra sức khỏe cho gateway và Eureka: trả trạng thái UP và tên service."""
    return {"status": "UP", "service": settings.app_name}

app.include_router(
    customer.router,
    prefix="/api/customer",
    tags=["Customer API"]
)

app.include_router(
    organizer.router,
    prefix="/api/organizer",
    tags=["Organizer API"]
)

app.include_router(
    admin.router,
    prefix="/api/admin",
    tags=["Admin API"]
)
