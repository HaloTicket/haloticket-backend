import os
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, declarative_base

# read URL connect from .env
DATABASE_URL = os.getenv("DATABASE_URL", "postgresql+psycopg2://haloticket:secretpassword@localhost:5433/event_db")


def make_engine(url: str):
    """One place for the pool settings of every engine in this service.

    pool_pre_ping: a connection the database dropped (restart, idle timeout) is replaced before use instead of failing
    the request. pool_recycle: connections are renewed before a server or proxy closes them on its own.
    """
    return create_engine(url, pool_pre_ping=True, pool_recycle=1800)


# init engine
engine = make_engine(DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
