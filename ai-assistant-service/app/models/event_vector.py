from sqlalchemy import Column, String
from pgvector.sqlalchemy import Vector
from app.db.session import Base

class EventVector(Base):
    __tablename__ = "event_vectors"

    # define the columns in the table
    id = Column(String, primary_key=True, index=True)
    event_id = Column(String, unique=True, index=True, nullable=False)
    content = Column(String, nullable=False) # contains text content (Event name, description...)

    # gemini model has an output vector dimension of 3072
    embedding = Column(Vector(3072))