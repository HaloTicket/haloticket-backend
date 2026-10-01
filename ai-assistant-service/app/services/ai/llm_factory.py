from langchain_google_genai import ChatGoogleGenerativeAI, GoogleGenerativeAIEmbeddings
from langchain_groq import ChatGroq
from langchain_openai import ChatOpenAI
from app.core.config import settings

def _get_gemini_keys():
    keys = settings.gemini_api_key.split(",")
    return [k.strip() for k in keys if k.strip()]

def get_fast_llm(tools=None):
    """Dùng cho Khách hàng: Chatbot, Tìm kiếm, Chọn ghế (Tốc độ cao)"""
    models = []
    
    # 1. Groq (Llama 3.1 70B - Fastest, Native tool calling)
    if settings.groq_api_key:
        model = ChatGroq(
            api_key=settings.groq_api_key,
            model="openai/gpt-oss-20b",
            temperature=0.2,
            max_retries=1
        )
        if tools: model = model.bind_tools(tools)
        models.append(model)
        
    # 2. OpenAI (GPT-4o-mini - Stable)
    if settings.openai_api_key:
        model = ChatOpenAI(
            api_key=settings.openai_api_key,
            model="gpt-4o-mini",
            temperature=0.2,
            max_retries=1
        )
        if tools: model = model.bind_tools(tools)
        models.append(model)
        
    # 3. Gemini Flash
    gemini_keys = _get_gemini_keys()
    for key in gemini_keys:
        model = ChatGoogleGenerativeAI(
            api_key=key,
            model="gemini-flash-latest",
            temperature=0.2,
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
    """Used for Admin/Organizer: Text-to-SQL, Risk Scoring (Tư duy sâu)"""
    models = []
    
    # 1. Groq (Llama 3.1 70B)
    if settings.groq_api_key:
        model = ChatGroq(
            api_key=settings.groq_api_key,
            model="openai/gpt-oss-120b",
            temperature=0.0,
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
            max_retries=1
        )
        if tools: model = model.bind_tools(tools)
        models.append(model)
        
    # 3. Gemini Pro
    gemini_keys = _get_gemini_keys()
    for key in gemini_keys:
        model = ChatGoogleGenerativeAI(
            api_key=key,
            model="gemini-pro-latest",
            temperature=0.0,
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