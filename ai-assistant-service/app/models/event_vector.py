"""Mô hình bảng vector hóa sự kiện dùng cho tìm kiếm ngữ nghĩa."""
from sqlalchemy import Column, String
from pgvector.sqlalchemy import Vector
from app.db.session import Base

class EventVector(Base):
    """Một dòng trong bảng `event_vectors`: nội dung văn bản của sự kiện và vector embedding của nó."""
    __tablename__ = "event_vectors"

    # khai báo các cột của bảng
    id = Column(String, primary_key=True, index=True)
    event_id = Column(String, unique=True, index=True, nullable=False)
    content = Column(String, nullable=False) # chứa nội dung văn bản (tên sự kiện, mô tả...)

    # mô hình Gemini cho vector đầu ra có số chiều 3072
    embedding = Column(Vector(3072))