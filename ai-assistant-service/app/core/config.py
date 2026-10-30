from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    app_name: str = "ai-assistant-service"
    port: int = 8089
    eureka_server: str = "http://localhost:8761/eureka"
    database_url: str = "postgresql+psycopg2://haloticket:secretpassword@localhost:5433/event_db"
    gemini_api_key: str = "your-default-key-if-missing"
    groq_api_key: str = ""
    openai_api_key: str = ""
    kafka_bootstrap_servers: str = "localhost:9092"
    llm_timeout_seconds: int = 45  # per call to a chat model; LLM_TIMEOUT_SECONDS overrides

    # verification of the Keycloak access token on the admin / analysis routes
    keycloak_issuer_uri: str = ""
    keycloak_jwk_set_uri: str = ""
    auth_audience: str = ""  # optional; empty = audience not checked, as at the gateway

    # data analysis (text-to-SQL). the account is a read-only role that sees only the analysis views.
    # Both are required: without them the analysis routes answer 503 AI_DB_NOT_CONFIGURED (the service itself still starts).
    ai_db_user: str = ""
    ai_db_password: str = ""
    ai_analysis_schema: str = "ai_analytics"
    ai_sql_allowed_relations: str = (
        "v_events,v_event_showtimes,v_ticket_types,v_ticket_sales_daily,v_booking_summary_daily,"
        "v_event_views_daily,v_vouchers,v_venues,v_event_categories,v_search_terms"
    )
    ai_sql_max_rows: int = 500
    ai_sql_timeout_ms: int = 5000
    ai_sql_max_result_chars: int = 200000

    # data analysis for organizers: limited to the events they may manage
    ai_scope_columns: str = (
        "v_events:event_id,v_event_showtimes:event_id,v_ticket_types:event_id,v_ticket_sales_daily:event_id,"
        "v_booking_summary_daily:event_id,v_event_views_daily:event_id,v_vouchers:event_id"
    )  # relation:column that holds the event id
    ai_scope_public_relations: str = "v_venues,v_event_categories"  # no event id, public information
    ai_scope_max_events: int = 500
    user_service_url: str = "http://user-service:8081"  # to turn the token into the user-service user id
    user_service_timeout_seconds: float = 8.0  # the first call after a user-service start can be slow

    # admin screens
    ai_system_log_file: str = "/app/event-error.log"
    ai_system_log_max_lines: int = 25
    alert_ssl_domains: str = "haloticket.duckdns.org"  # ALERT_SSL_DOMAINS: the only domains /ssl-status will look at


    class Config:
        env_file = ".env"
        extra = "ignore" # skip undefined env

settings = Settings()