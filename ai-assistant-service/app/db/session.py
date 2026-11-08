import os
import logging
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, declarative_base

log = logging.getLogger("ai.db")

CONNECT_TIMEOUT_SECONDS = 5


def make_engine(url, **options):
    """One place for the pool settings of every engine in this service.

    pool_pre_ping: a connection the database dropped (restart, idle timeout) is replaced before use instead of failing
    the request. pool_recycle: connections are renewed before a server or proxy closes them on its own.
    connect_timeout: an unreachable database fails in 5 s instead of hanging.
    """
    connect_args = dict(options.pop("connect_args", None) or {})
    connect_args.setdefault("connect_timeout", CONNECT_TIMEOUT_SECONDS)
    return create_engine(url, pool_pre_ping=True, pool_recycle=1800, connect_args=connect_args, **options)


# read URL from the environment; there is no default credential. Without it the engine is None and get_db answers
# AI_DB_NOT_CONFIGURED, while the service itself still starts.
DATABASE_URL = os.getenv("DATABASE_URL", "")

engine = None
SessionLocal = sessionmaker(autocommit=False, autoflush=False)
if DATABASE_URL:
    try:
        engine = make_engine(DATABASE_URL)
        SessionLocal.configure(bind=engine)
    except Exception as failure:  # bad URL or missing driver: do not crash the import
        log.error("Could not build the shared engine: %s", type(failure).__name__)
        engine = None
Base = declarative_base()


def get_db():
    if engine is None:
        from app.core.errors import ai_error, AI_DB_NOT_CONFIGURED
        raise ai_error(AI_DB_NOT_CONFIGURED)
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
