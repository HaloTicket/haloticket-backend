# HaloTicket Backend

Hệ thống backend microservice và hạ tầng Docker cho nền tảng bán vé sự kiện **HaloTicket**.

## Kiến trúc và cấu trúc thư mục

```text
haloticket-backend/
├── api-gateway/               # Cổng API trung tâm, lọc bảo mật (cổng 8888)
├── discovery-server/          # Eureka Service Registry (cổng 8761)
├── user-service/              # Xác thực, hồ sơ, OTP, đồng bộ Keycloak (cổng 8081)
├── event-service/             # Sự kiện, danh mục, địa điểm, phân tích tìm kiếm (cổng 8082)
├── ticket-service/            # Kho vé, hạng vé, quy tắc giá, check-in (cổng 8083)
├── booking-service/           # Đặt vé, giữ chỗ, voucher (cổng 8084)
├── payment-service/           # Thanh toán và tích hợp cổng thanh toán (cổng 8085)
├── notification-service/      # Gửi email, nhận OTP từ Kafka (cổng 8086)
├── media-service/             # Tải ảnh lên và lưu trữ S3 (cổng 8087)
├── marketplace-service/       # Chợ bán lại vé
├── ai-assistant-service/      # Chatbot và trợ lý AI, FastAPI (cổng 8089)
├── haloticket-infrastructure/ # Script khởi tạo PostgreSQL, realm và theme Keycloak
├── docker-compose.yml         # Khai báo Compose cho toàn bộ container
├── Dockerfile                 # Build nhiều tầng trên Eclipse Temurin 21 JRE Alpine
├── .env.example               # Mẫu biến môi trường
└── DEPLOYMENT.md              # Hướng dẫn triển khai lên máy chủ
```

## Công nghệ

- Java 21, Spring Boot 4.1, Spring Cloud 2025.1 (Gateway, Eureka)
- Python, FastAPI, LangChain (ai-assistant-service)
- Keycloak 24 (OAuth2/OIDC, đăng nhập Google)
- PostgreSQL 16 (có pgvector), Redis 7, Apache Kafka 3.7 (KRaft)
- Docker và Docker Compose

## Bảng dịch vụ và cổng

| Dịch vụ | Cổng host | Cổng container | Vai trò |
|---|---:|---:|---|
| API Gateway | 8888 | 8888 | Định tuyến `/api/**`, lọc bảo mật |
| Discovery Server | 8761 | 8761 | Eureka Service Registry |
| User Service | 8081 | 8081 | Tài khoản, xác thực mật khẩu, OTP |
| Event Service | 8082 | 8082 | Sự kiện, ban tổ chức, danh mục |
| Ticket Service | 8083 | 8083 | Kho vé, giá theo hạng, check-in |
| Booking Service | 8084 | 8084 | Đơn hàng, giữ chỗ vé |
| Payment Service | 8085 | 8085 | Thanh toán, webhook |
| Notification Service | 8086 | 8086 | Email, consumer Kafka |
| Media Service | 8087 | 8087 | Tải ảnh lên, lưu trữ S3 |
| AI Assistant Service | 8089 | 8089 | Chatbot, tìm kiếm AI, trợ lý cho ban tổ chức và admin |
| Keycloak | 9080 | 8080 | OAuth2/OIDC, phân quyền |
| PostgreSQL | 5433 | 5432 | Cơ sở dữ liệu quan hệ (nhiều database) |
| Redis | 6379 | 6379 | Cache, khóa phân tán, giới hạn tốc độ AI |
| Kafka | 9092 | 9092 | Message broker |

## Yêu cầu

- Docker và Docker Compose
- JDK 21 (khi chạy service trực tiếp bằng Maven)
- Python 3.11 trở lên (khi chạy ai-assistant-service ngoài Docker)

## Cấu hình

Sao chép file mẫu rồi điền giá trị thật:

```bash
cp .env.example .env
```

Trên Windows PowerShell: `Copy-Item .env.example .env`

Các biến quan trọng trong `.env`:

- `POSTGRES_USER`, `POSTGRES_PASSWORD`: tài khoản cơ sở dữ liệu.
- `REDIS_PASSWORD`: mật khẩu Redis.
- `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`: tài khoản quản trị Keycloak.
- `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`: đăng nhập Google.
- `SMTP_USERNAME`, `SMTP_PASSWORD`: gửi email OTP và email giao dịch.
- `AUTHZ_EVENT_SCOPE_INTERNAL_TOKEN`, `TICKET_RESERVATION_INTERNAL_TOKEN`: bí mật dùng chung cho các lời gọi nội bộ giữa service. Bắt buộc, không để trống.
- `GEMINI_API_KEY`, `GROQ_API_KEY`, `OPENAI_API_KEY`: cần ít nhất một khóa để dùng tính năng AI.
- Thông tin cổng thanh toán (VNPAY, SePay, ZaloPay, ShopeePay, MoMo) và S3 cho media-service.

Không commit file `.env`. Chi tiết từng biến có chú thích trong `.env.example`.

## Chạy toàn bộ hệ thống bằng Docker

```bash
docker compose up -d
docker compose ps
```

Khi các container ở trạng thái `healthy`:

- API Gateway: `http://localhost:8888`
- Eureka: `http://localhost:8761`
- Keycloak: `http://localhost:9080`
- Frontend: `http://localhost:5173` (chạy từ `haloticket-web-app`)

Dừng hệ thống:

```bash
docker compose down
```

## Chạy từng service trong IDE hoặc terminal

Giữ hạ tầng trong Docker, chạy service cần debug ở máy.

1. Khởi động hạ tầng:

   ```bash
   docker compose up -d postgres redis kafka keycloak
   ```

2. Nếu toàn bộ stack đang chạy, dừng các service Java trong Docker để nhường cổng:

   ```bash
   docker compose stop discovery-server api-gateway user-service event-service ticket-service booking-service payment-service notification-service media-service
   ```

3. Chạy service cần dùng, ví dụ `user-service`:

   ```bash
   cd user-service
   ./mvnw spring-boot:run
   ```

   Trên Windows PowerShell: `.\mvnw.cmd spring-boot:run`

## AI Assistant

`ai-assistant-service` (Python, FastAPI, cổng 8089) phục vụ:

- chatbot và tìm kiếm AI cho khách hàng (`/api/customer/**`),
- công cụ cho ban tổ chức (`/api/organizer/**`),
- trợ lý, chấm điểm rủi ro và báo cáo cho admin (`/api/admin/**`).

Dịch vụ thử Groq trước, sau đó OpenAI hoặc Gemini. Truy cập dữ liệu bằng các role PostgreSQL chỉ đọc, script tạo role nằm trong `haloticket-infrastructure/postgres/`. Các biến cấu hình và giới hạn tốc độ nằm cuối `.env.example`.

Nghiên cứu về chấm điểm rủi ro nằm trong `ai-assistant-service/research/risk/`.

## Triển khai chi phí thấp

`docker-compose.yml` đã được tối ưu bộ nhớ và dung lượng đĩa:

- JVM dùng SerialGC và stack luồng 256k, giảm khoảng 450MB bộ nhớ.
- Tổng giới hạn bộ nhớ các container khoảng 3,55 GB, chạy ổn trên VPS 4GB (hoặc 2GB có swap).
- Log Docker xoay vòng, tối đa 3MB x 2 file mỗi container.

Các bước triển khai thật, reverse proxy và swap xem trong [DEPLOYMENT.md](DEPLOYMENT.md).

## Kiểm thử

Dùng Maven wrapper trong từng service:

```bash
cd user-service
./mvnw test
```

Với ai-assistant-service:

```bash
cd ai-assistant-service
pytest
```
