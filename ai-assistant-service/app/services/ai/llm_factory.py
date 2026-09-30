from langchain_google_genai import ChatGoogleGenerativeAI, GoogleGenerativeAIEmbeddings
from app.core.config import settings
import os

# set API Key for LangChain
os.environ['GOOGLE_API_KEY'] = settings.gemini_api_key

def get_fast_llm():
    """Used for Customers: Chatbot, Search, Seat Selection (High Speed)"""
    return ChatGoogleGenerativeAI(
        model="gemini-flash-latest",
        temperature=0.2 # reduce creativity to provide more accurate data-based responses
    )

def get_smart_llm():
    """Used for Admin/Organizer: Text-to-SQL, Risk Scoring (Tư duy sâu)"""
    return ChatGoogleGenerativeAI(
        model="gemini-pro-latest",
        temperature=0.0 # set temperature=0 to ensure the AI ​​writes perfectly accurate SQL.
    )

def get_embeddings():
    """Used to convert text into vectors (768 dimensions)."""
    return GoogleGenerativeAIEmbeddings(
        model="models/gemini-embedding-2"
    )