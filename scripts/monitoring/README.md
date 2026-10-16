# HaloTicket System Monitoring & Alert Watchdog

Hệ thống script tự động giám sát sức khỏe 12 Microservices, phát hiện log lỗi, kiểm tra hạn chứng chỉ SSL và tự động gửi cảnh báo khẩn cấp về **Email** và **Zalo** của Quản trị viên.

---

## 1. Hướng dẫn chạy Script Cảnh báo (Email & Zalo)

### Cách 1: Chạy bằng Python (Khuyên dùng)
```bash
# 1. Cài đặt thư viện phụ thuộc (nếu chưa có)
pip install requests urllib3

# 2. Gửi thử nghiệm một tin cảnh báo kiểm tra kết nối
python haloticket-backend/scripts/monitoring/alert_notifier.py --test-alert

# 3. Kiểm tra riêng hạn chứng chỉ SSL các domain ngay lập tức
python haloticket-backend/scripts/monitoring/alert_notifier.py --check-ssl

# 4. Chạy chế độ giám sát tự động liên tục 24/7
# (Microservices quét mỗi 60s, SSL quét 1 lần/ngày vào lúc 09:00 sáng)
python haloticket-backend/scripts/monitoring/alert_notifier.py --watch --interval 60
```

### Cách 2: Chạy bằng PowerShell (Windows)
```powershell
# Chạy kiểm tra một lần
powershell -ExecutionPolicy Bypass -File haloticket-backend/scripts/monitoring/alert_notifier.ps1

# Gửi tin cảnh báo thử nghiệm
powershell -ExecutionPolicy Bypass -File haloticket-backend/scripts/monitoring/alert_notifier.ps1 -TestAlert

# Chạy giám sát tự động
powershell -ExecutionPolicy Bypass -File haloticket-backend/scripts/monitoring/alert_notifier.ps1 -Watch -IntervalSeconds 60
```

---

## 2. Cấu hình Biến Môi Trường (Environment Variables)

Bạn có thể cấu hình trong file `.env` hoặc truyền biến môi trường trước khi chạy script:

| Biến Môi Trường | Mặc định | Ý nghĩa |
|---|---|---|
| `SMTP_SERVER` | `smtp.gmail.com` | Máy chủ gửi thư SMTP |
| `SMTP_PORT` | `587` | Cổng SMTP TLS |
| `ALERT_SMTP_USERNAME` | `alerts@haloticket.vn` | Email người gửi |
| `ALERT_SMTP_PASSWORD` | App Password | Mật khẩu ứng dụng Gmail (16 ký tự) |
| `ALERT_ADMIN_EMAILS` | `admin@haloticket.vn` | Danh sách email nhận cảnh báo (phân cách dấu phẩy) |
| `ALERT_TELEGRAM_BOT_TOKEN` | - | Bot Token tạo từ @BotFather trên Telegram |
| `ALERT_TELEGRAM_CHAT_ID` | - | Chat ID người nhận lấy từ @userinfobot |
| `ALERT_ZALO_ACCESS_TOKEN` | - | Token truy cập của Zalo Official Account |
| `ALERT_ZALO_ADMIN_USER_ID` | - | User ID người nhận trên Zalo OA |
| `ALERT_SSL_DOMAINS` | `haloticket.vn` | Danh sách domain cần kiểm tra hạn SSL cổng 443 |
| `ALERT_SSL_WARN_DAYS` | `15` | Ngưỡng số ngày còn lại để bắt đầu gửi cảnh báo |
| `ALERT_SSL_CHECK_SCHEDULE` | `09:00` | Khung giờ cố định mỗi ngày quét SSL (HH:MM) |
| `ALERT_CHECK_INTERVAL_SECONDS` | `60` | Chu kỳ quét trạng thái Microservices & Logs (giây) |
| `ALERT_LOG_FILE_PATH` | `event-error.log` | Đường dẫn file log hệ thống cần quét |

---

### Cách lấy Telegram Bot Token & Chat ID (Chỉ 30 giây):
1. **Lấy Bot Token:** Mở Telegram tìm bot `@BotFather` $\rightarrow$ Gõ lệnh `/newbot` $\rightarrow$ Nhập tên bot và username $\rightarrow$ Nhận chuỗi `TOKEN` (ví dụ `7123456789:AAH...`).
2. **Kích hoạt bot:** Bấm vào đường link bot vừa tạo $\rightarrow$ Nhấn nút **START**.
3. **Lấy Chat ID:** Mở Telegram tìm bot `@userinfobot` $\rightarrow$ Nhấn **START** $\rightarrow$ Bot sẽ trả về dãy số `Id` của bạn (ví dụ `123456789`).
4. Điền vào `.env`:
   ```env
   ALERT_TELEGRAM_BOT_TOKEN=7123456789:AAH...
   ALERT_TELEGRAM_CHAT_ID=123456789
   ```

---

## 3. Cài đặt Chạy Ngầm (Daemon / Cronjob) Trên Server

### Trên Linux (Ubuntu / Debian Server qua Crontab):
Chạy lệnh `crontab -e` và thêm dòng sau để script chạy mỗi phút:
```cron
* * * * * cd /var/www/haloticket && python3 haloticket-backend/scripts/monitoring/alert_notifier.py >> /var/log/haloticket-monitor.log 2>&1
```

### Hoặc tạo Systemd Service (`/etc/systemd/system/haloticket-monitor.service`):
```ini
[Unit]
Description=HaloTicket Monitoring & Alert Daemon
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=/var/www/haloticket
ExecStart=/usr/bin/python3 haloticket-backend/scripts/monitoring/alert_notifier.py --watch --interval 60
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```
Kích hoạt service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now haloticket-monitor.service
```

---

## 4. Hướng Dẫn Giám Sát Chứng Chỉ SSL/TLS

### 4.1. Giám sát tự động qua script (Đã tích hợp trong `alert_notifier.py`)
Hàm `check_ssl_certificate(domain)` sử dụng thư viện chuẩn `ssl` và `socket` để bắt tay TLS với domain và trích xuất ngày hết hạn (`notAfter`). Khi số ngày còn lại **dưới 15 ngày**, hệ thống tự động bắn cảnh báo khẩn về Email và Zalo.

### 4.2. Cấu hình tự động gia hạn Let's Encrypt (Certbot)
1. Kiểm tra trạng thái tự động renew của Certbot:
   ```bash
   sudo certbot renew --dry-run
   ```
2. Đảm bảo timer tự động của systemd đang hoạt động:
   ```bash
   sudo systemctl status certbot.timer
   ```
   *(Certbot sẽ tự động kiểm tra 2 lần/ngày và tự động renew khi chứng chỉ còn dưới 30 ngày).*

### 4.3. Giám sát bằng công cụ miễn phí bên thứ ba (Khuyên dùng kết hợp)
- **UptimeRobot / Better Uptime (BetterStack):** Tạo monitor với kiểu `SSL Certificate Monitor`. Hệ thống đám mây sẽ kiểm tra từ nhiều địa điểm toàn cầu và gửi email/SMS trước 30, 14, 7 ngày.
- **Cloudflare SSL/TLS:** Nếu phân giải DNS qua Cloudflare, bật **Universal SSL** và **Always Use HTTPS** để Cloudflare tự động cấp phát và gia hạn chứng chỉ tự động 100%.
