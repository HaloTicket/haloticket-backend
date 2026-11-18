"""Dựng các mô hình LLM và embedding (Groq, OpenAI, Gemini) kèm cơ chế dự phòng theo thứ tự ưu tiên."""
from langchain_google_genai import ChatGoogleGenerativeAI, GoogleGenerativeAIEmbeddings
from langchain_groq import ChatGroq
from langchain_openai import ChatOpenAI
from app.core.config import settings

def _get_gemini_keys():
    """Tách chuỗi GEMINI_API_KEY (phân cách bằng dấu phẩy) thành danh sách khóa; rỗng nếu chưa cấu hình."""
    if not settings.gemini_api_key: return []
    keys = settings.gemini_api_key.split(",")
    return [k.strip() for k in keys if k.strip()]

def _get_groq_keys():
    """Tách chuỗi GROQ_API_KEY (phân cách bằng dấu phẩy) thành danh sách khóa; rỗng nếu chưa cấu hình."""
    if not settings.groq_api_key: return []
    keys = settings.groq_api_key.split(",")
    return [k.strip() for k in keys if k.strip()]

def get_fast_llm(tools=None):
    """Dùng cho Khách hàng: Chatbot, Tìm kiếm, Chọn ghế (Tốc độ cao). Trả về LLM đầu tiên khả dụng, các LLM còn lại làm dự phòng (with_fallbacks); gắn tools nếu có."""
    models = []
    
    # 1. Groq (nhanh nhất, hỗ trợ gọi tool gốc)
    groq_keys = _get_groq_keys()
    for key in groq_keys:
        model = ChatGroq(
            api_key=key,
            model="openai/gpt-oss-20b",
            temperature=0.2,
            timeout=settings.llm_timeout_seconds,
            max_retries=1
        )
        if tools: model = model.bind_tools(tools)
        models.append(model)
        
    # 2. OpenAI (GPT-4o-mini - ổn định)
    if settings.openai_api_key:
        model = ChatOpenAI(
            api_key=settings.openai_api_key,
            model="gpt-4o-mini",
            temperature=0.2,
            timeout=settings.llm_timeout_seconds,
            max_retries=1
        )
        if tools: model = model.bind_tools(tools)
        models.append(model)
        
    # 3. Gemini Flash (dự phòng cuối)
    gemini_keys = _get_gemini_keys()
    for key in gemini_keys:
        model = ChatGoogleGenerativeAI(
            api_key=key,
            model="gemini-flash-latest",
            temperature=0.2,
            timeout=settings.llm_timeout_seconds,
            max_retries=1
        )
        if tools: model = model.bind_tools(tools)
        models.append(model)
        
    if not models:
        raise ValueError("No API Keys provided for any LLM provider!")
        
    primary_llm = models[0]
    if len(models) > 1:
        return primary_llm.with_fallbacks(models[1:])
    return primary_llm

def get_smart_llm(tools=None):
    """Dùng cho Admin/Organizer: Text-to-SQL, chấm điểm rủi ro (tư duy sâu, temperature 0). Cùng cơ chế dự phòng như get_fast_llm."""
    models = []
    
    # 1. Groq (mô hình lớn hơn)
    groq_keys = _get_groq_keys()
    for key in groq_keys:
        model = ChatGroq(
            api_key=key,
            model="openai/gpt-oss-120b",
            temperature=0.0,
            timeout=settings.llm_timeout_seconds,
            max_retries=1
        )
        if tools: model = model.bind_tools(tools)
        models.append(model)
        
    # 2. OpenAI (GPT-4o-mini)
    if settings.openai_api_key:
        model = ChatOpenAI(
            api_key=settings.openai_api_key,
            model="gpt-4o-mini",
            temperature=0.0,
            timeout=settings.llm_timeout_seconds,
            max_retries=1
        )
        if tools: model = model.bind_tools(tools)
        models.append(model)
        
    # 3. Gemini Pro (dự phòng cuối)
    gemini_keys = _get_gemini_keys()
    for key in gemini_keys:
        model = ChatGoogleGenerativeAI(
            api_key=key,
            model="gemini-pro-latest",
            temperature=0.0,
            timeout=settings.llm_timeout_seconds,
            max_retries=1
        )
        if tools: model = model.bind_tools(tools)
        models.append(model)
        
    if not models:
        raise ValueError("No API Keys provided for any LLM provider!")
        
    primary_llm = models[0]
    if len(models) > 1:
        return primary_llm.with_fallbacks(models[1:])
    return primary_llm

def get_embeddings():
    """Dùng chung để chuyển chữ thành Vector (3072 chiều)"""
    gemini_keys = _get_gemini_keys()
    if not gemini_keys:
        raise ValueError("No GEMINI_API_KEY provided for embeddings")
        
    return GoogleGenerativeAIEmbeddings(
        google_api_key=gemini_keys[0],
        model="models/gemini-embedding-2"
    )