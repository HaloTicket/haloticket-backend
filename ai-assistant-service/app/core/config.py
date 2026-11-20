"""Cấu hình của dịch vụ AI, đọc từ biến môi trường hoặc tệp `.env`."""
from pydantic_settings import BaseSettings

class Settings(BaseSettings):
    """Toàn bộ thông số cấu hình: kết nối, khóa API, giới hạn tần suất, ngưỡng rủi ro và các mốc cảnh báo của trang quản trị."""
    app_name: str = "ai-assistant-service"
    port: int = 8089
    eureka_server: str = "http://localhost:8761/eureka"
    database_url: str = ""  # DATABASE_URL; không có thông tin đăng nhập mặc định. Để trống = các route dùng DB trả 503 AI_DB_NOT_CONFIGURED
    gemini_api_key: str = "your-default-key-if-missing"
    groq_api_key: str = ""
    openai_api_key: str = ""
    llm_timeout_seconds: int = 45  # cho mỗi lần gọi mô hình chat; biến LLM_TIMEOUT_SECONDS sẽ ghi đè

    # xác thực access token của Keycloak trên các route quản trị / phân tích
    keycloak_issuer_uri: str = ""
    keycloak_jwk_set_uri: str = ""
    auth_audience: str = ""  # tùy chọn; để trống = không kiểm tra audience, giống ở gateway

    # phân tích dữ liệu (text-to-SQL). Tài khoản là role chỉ đọc, chỉ thấy các view phân tích.
    # Cả hai đều bắt buộc: thiếu thì các route phân tích trả 503 AI_DB_NOT_CONFIGURED (bản thân dịch vụ vẫn khởi động).
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

    # phân tích dữ liệu cho organizer: chỉ trong phạm vi các sự kiện họ được quản lý
    ai_scope_columns: str = (
        "v_events:event_id,v_event_showtimes:event_id,v_ticket_types:event_id,v_ticket_sales_daily:event_id,"
        "v_booking_summary_daily:event_id,v_event_views_daily:event_id,v_vouchers:event_id"
    )  # dạng relation:column, cột chứa mã sự kiện
    ai_scope_public_relations: str = "v_venues,v_event_categories"  # không có mã sự kiện, là thông tin công khai
    ai_scope_max_events: int = 500
    user_service_url: str = "http://user-service:8081"  # để đổi token thành mã người dùng của user-service
    user_service_timeout_seconds: float = 8.0  # lần gọi đầu tiên sau khi user-service khởi động có thể chậm

    # các màn hình quản trị
    ai_system_log_file: str = "/app/event-error.log"  # tệp đơn kiểu cũ; chỉ dùng khi ai_system_log_dir để trống
    ai_system_log_dir: str = ""  # AI_SYSTEM_LOG_DIR: thư mục chứa mỗi dịch vụ một tệp <service>.log (compose gắn ở chế độ chỉ đọc)
    ai_system_log_max_lines: int = 100
    ai_actuator_port: int = 9091  # cổng quản lý của các dịch vụ Spring (không mở ra ngoài mạng compose)
    alert_ssl_domains: str = "haloticket.duckdns.org"  # ALERT_SSL_DOMAINS: các tên miền duy nhất mà /ssl-status được phép kiểm tra

    # gọi mô hình (app/services/ai/ai_call.py): chỉ gồm các giới hạn số
    ai_llm_deadline_seconds: int = 45
    ai_max_tokens_customer_chat: int = 600
    ai_max_tokens_admin_assistant: int = 700
    ai_max_tokens_risk_scoring: int = 800
    ai_max_tokens_event_content: int = 1200
    ai_max_tokens_analysis: int = 900
    ai_max_tokens_voucher_strategy: int = 900
    ai_max_tokens_dashboard_summary: int = 800
    ai_daily_call_budget: int = 2000
    # phần tối đa của ngân sách ngày mà người chưa đăng nhập được dùng; phần còn lại luôn dành cho người đã đăng nhập
    ai_anonymous_budget_share: float = 0.5
    ai_search_cache_seconds: int = 300
    ai_trusted_proxy_cidrs: str = "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,127.0.0.0/8,::1"

    # địa chỉ kết nối (để trống = tính năng suy giảm, dịch vụ vẫn khởi động)
    redis_url: str = ""
    ai_ops_database_url: str = ""
    ai_admin_database_url: str = ""  # role ai_admin_rw trên ai_ops: đánh giá rủi ro, quyết định, ghim, tóm tắt bảng điều khiển
    internal_api_base_url: str = "http://api-gateway:8888"  # dữ liệu quản trị được đọc qua gateway bằng chính token của admin
    internal_api_timeout_seconds: float = 3.0
    chatbot_event_database_url: str = ""
    chatbot_ticket_database_url: str = ""
    mongodb_uri: str = ""
    mongodb_database: str = "haloticket_ai_chat"

    # giới hạn tần suất (cửa sổ cố định)
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
    ai_rl_organizer_chat_per_hour: int = 20
    ai_rl_organizer_chat_per_day: int = 100
    ai_rl_admin_assistant_per_hour: int = 60
    ai_rl_risk_scoring_per_hour: int = 60
    ai_rl_dashboard_summary_per_day: int = 10

    # phần thông minh cho quản trị (đánh giá rủi ro, KPI bảng điều khiển, mục cần chú ý). Chỉ gồm số; mỗi mục đều có mặc định.
    risk_medium_threshold: int = 30
    risk_high_threshold: int = 60
    risk_block_floor: int = 70  # luật BLOCK (hàng cấm, cam kết lợi nhuận) không bao giờ có điểm thấp hơn mức này
    risk_ai_max_points: int = 30  # lớp mô hình ngôn ngữ chỉ được cộng tối đa chừng này điểm
    risk_organizer_signal_cap: int = 30  # tuổi tài khoản, eKYC và lịch sử bị từ chối có tương quan với nhau: cộng lại tối đa chừng này điểm
    attn_high_risk_pending_hours: int = 12
    attn_critical_pending_hours: int = 24
    attn_late_payment_minutes: int = 30
    attn_paid_without_tickets_minutes: int = 15
    attn_resale_pending_hours: int = 24
    ai_pins_max_per_admin: int = 12
    admin_cache_seconds: int = 30  # danh sách cần chú ý; bộ đệm KPI gấp đôi giá trị này

    class Config:
        """Cấu hình pydantic: đọc biến môi trường từ tệp .env và bỏ qua biến không khai báo."""
        env_file = [".env", "../.env"]
        extra = "ignore" # bỏ qua biến môi trường không khai báo

settings = Settings()
