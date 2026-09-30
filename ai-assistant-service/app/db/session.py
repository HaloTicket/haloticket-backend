import os
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, declarative_base

# read URL connect from .env
DATABASE_URL = os.getenv("DATABASE_URL", "postgresql+psycopg2://haloticket:secretpassword@localhost:5433/event_db")

# init engine
engine = create_engine(DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()