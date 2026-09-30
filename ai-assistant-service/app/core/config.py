from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    app_name: str = "ai-assistant-service"
    port: int = 8089
    eureka_server: str = "http://localhost:8761/eureka"
    database_url: str = "postgresql+psycopg2://haloticket:secretpassword@localhost:5433/event_db"
    gemini_api_key: str = "your-default-key-if-missing"
    kafka_bootstrap_servers: str = "localhost:9092"


    class Config:
        env_file = ".env"
        extra = "ignore" # skip undefined env

settings = Settings()