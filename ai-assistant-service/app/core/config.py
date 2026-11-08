from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    app_name: str = "ai-assistant-service"
    port: int = 8089
    eureka_server: str = "http://localhost:8761/eureka"
    database_url: str = ""  # DATABASE_URL; no default credentials. Empty = DB-backed routes answer 503 AI_DB_NOT_CONFIGURED
    gemini_api_key: str = "your-default-key-if-missing"
    groq_api_key: str = ""
    openai_api_key: str = ""
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

    # model calls (app/services/ai/ai_call.py): numeric limits only
    ai_llm_deadline_seconds: int = 20
    ai_max_tokens_customer_chat: int = 600
    ai_max_tokens_admin_assistant: int = 700
    ai_max_tokens_risk_scoring: int = 400
    ai_max_tokens_event_content: int = 1200
    ai_max_tokens_analysis: int = 900
    ai_max_tokens_voucher_strategy: int = 900
    ai_daily_call_budget: int = 2000
    ai_search_cache_seconds: int = 300
    ai_trusted_proxy_cidrs: str = "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,127.0.0.0/8,::1"

    # addresses (empty = feature degrades, service still starts)
    redis_url: str = ""
    ai_ops_database_url: str = ""
    chatbot_event_database_url: str = ""
    chatbot_ticket_database_url: str = ""

    # rate limits (fixed windows)
    ai_rl_customer_search_per_minute: int = 20
    ai_rl_customer_chat_ip_per_minute: int = 6
    ai_rl_customer_chat_ip_per_hour: int = 60
    ai_rl_customer_chat_account_per_minute: int = 10
    ai_rl_customer_chat_account_per_hour: int = 120
    ai_rl_event_content_per_hour: int = 5
    ai_rl_event_content_per_day: int = 20
    ai_rl_voucher_strategy_per_hour: int = 10
    ai_rl_voucher_strategy_per_day: int = 30
    ai_rl_data_analysis_per_hour: int = 20
    ai_rl_data_analysis_per_day: int = 100
    ai_rl_admin_assistant_per_hour: int = 60
    ai_rl_risk_scoring_per_hour: int = 60

    class Config:
        env_file = ".env"
        extra = "ignore" # skip undefined env

settings = Settings()