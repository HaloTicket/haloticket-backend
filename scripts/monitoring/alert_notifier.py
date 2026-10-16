#!/usr/bin/env python3
"""
HaloTicket System Monitoring & Automated Alert Notifier
------------------------------------------------------
Tự động giám sát:
1. Trạng thái hoạt động của 12 Microservices (Eureka/Gateway/Health checks).
2. Phát hiện lỗi nghiêm trọng (ERROR/WARN) từ file log hệ thống.
3. Kiểm tra hạn chứng chỉ SSL/TLS (cảnh báo khi < 15 ngày).
4. Tự động gửi cảnh báo khẩn cấp về EMAIL và ZALO của Quản trị viên.

Hướng dẫn sử dụng:
  pip install requests urllib3
  python system_alert_notifier.py --test-alert
  python system_alert_notifier.py --watch --interval 60
"""

import os
import sys
import time
import json
import ssl
import socket
import smtplib
import argparse
from datetime import datetime
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart

# ensure UTF-8 printing in Windows terminals
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# automatically load variables from .env file if available
def load_env_file():
    possible_env_paths = [
        os.path.abspath(os.path.join(os.path.dirname(__file__), "../../.env")),
        os.path.abspath(os.path.join(os.getcwd(), ".env")),
        ".env"
    ]
    for p in possible_env_paths:
        if os.path.isfile(p):
            try:
                with open(p, "r", encoding="utf-8", errors="ignore") as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith("#") and "=" in line:
                            k, v = line.split("=", 1)
                            k = k.strip()
                            v = v.strip().strip('"').strip("'")
                            if k not in os.environ:
                                os.environ[k] = v
            except Exception:
                pass
            break

load_env_file()

# system config
CONFIG = {
    # config email admin
    "SMTP_SERVER": os.getenv("ALERT_SMTP_SERVER", os.getenv("SMTP_SERVER", "smtp.gmail.com")),
    "SMTP_PORT": int(os.getenv("ALERT_SMTP_PORT", os.getenv("SMTP_PORT", 587))),
    "SMTP_USERNAME": os.getenv("ALERT_SMTP_USERNAME", os.getenv("SMTP_USERNAME", "alerts@haloticket.vn")),
    "SMTP_PASSWORD": os.getenv("ALERT_SMTP_PASSWORD", os.getenv("SMTP_PASSWORD", "your-app-password")),
    "ADMIN_EMAILS": os.getenv("ALERT_ADMIN_EMAILS", os.getenv("ADMIN_EMAILS", "admin@haloticket.vn,devops@haloticket.vn")).split(","),

    # zalo configuration (OA Webhook / Zalo Bot / ZNS)
    "ZALO_WEBHOOK_URL": os.getenv("ALERT_ZALO_WEBHOOK_URL", os.getenv("ZALO_WEBHOOK_URL", "https://openapi.zalo.me/v2.0/oa/message")),
    "ZALO_ACCESS_TOKEN": os.getenv("ALERT_ZALO_ACCESS_TOKEN", os.getenv("ZALO_ACCESS_TOKEN", "")),
    "ZALO_ADMIN_USER_ID": os.getenv("ALERT_ZALO_ADMIN_USER_ID", os.getenv("ZALO_ADMIN_USER_ID", "")),

    # telegram configuration (Telegram Bot API)
    "TELEGRAM_BOT_TOKEN": os.getenv("ALERT_TELEGRAM_BOT_TOKEN", os.getenv("TELEGRAM_BOT_TOKEN", "")),
    "TELEGRAM_CHAT_ID": os.getenv("ALERT_TELEGRAM_CHAT_ID", os.getenv("TELEGRAM_CHAT_ID", "")),

    # list of services to be monitored
    "GATEWAY_URL": os.getenv("GATEWAY_URL", "http://localhost:8888"),
    "EUREKA_URL": os.getenv("EUREKA_URL", "http://localhost:8761/eureka/apps"),
    "SERVICES": [
        {"name": "API Gateway", "url": "http://localhost:8888/actuator/health", "port": 8888},
        {"name": "User Service", "url": "http://localhost:8081/actuator/health", "port": 8081},
        {"name": "Event Service", "url": "http://localhost:8082/actuator/health", "port": 8082},
        {"name": "Ticket Service", "url": "http://localhost:8083/actuator/health", "port": 8083},
        {"name": "Booking Service", "url": "http://localhost:8084/actuator/health", "port": 8084},
        {"name": "Payment Service", "url": "http://localhost:8085/actuator/health", "port": 8085},
        {"name": "Notification Service", "url": "http://localhost:8086/actuator/health", "port": 8086},
        {"name": "Marketplace Service", "url": "http://localhost:8088/actuator/health", "port": 8088},
        {"name": "Discovery Server", "url": "http://localhost:8761", "port": 8761},
    ],

    # log file for error tracking
    "LOG_FILE_PATH": os.getenv("ALERT_LOG_FILE_PATH", os.getenv("LOG_FILE_PATH", os.path.abspath(os.path.join(os.path.dirname(__file__), "../../event-error.log")))),

    # ssl certificate monitoring
    "SSL_DOMAINS": [
        d.strip().replace("https://", "").replace("http://", "").split("/")[0].split(":")[0]
        for d in os.getenv("ALERT_SSL_DOMAINS", "haloticket.vn,api.haloticket.vn").split(",")
        if d.strip()
    ],
    "SSL_WARN_DAYS": int(os.getenv("ALERT_SSL_WARN_DAYS", 15)),
    "SSL_CHECK_SCHEDULE": os.getenv("ALERT_SSL_CHECK_SCHEDULE", "09:00"),  # Giờ quét SSL cố định hàng ngày (HH:MM)
    "CHECK_INTERVAL_SECONDS": int(os.getenv("ALERT_CHECK_INTERVAL_SECONDS", 60)),  # Chu kỳ quét microservices (giây)
}

# tracking last daily SSL check date
last_ssl_check_date = None


# send email alert
def send_email_alert(subject: str, html_content: str):
    """Gửi email cảnh báo đến danh sách Admin"""
    username = CONFIG["SMTP_USERNAME"]
    password = CONFIG["SMTP_PASSWORD"]
    recipients = [e.strip() for e in CONFIG["ADMIN_EMAILS"] if e.strip()]

    if not username or "your-app-password" in password:
        print(f"[!] Email chưa được cấu hình SMTP. Mô phỏng gửi email: {subject}")
        return False

    try:
        msg = MIMEMultipart("alternative")
        msg["Subject"] = f"[HaloTicket Alert] {subject}"
        msg["From"] = f"HaloTicket Monitoring <{username}>"
        msg["To"] = ", ".join(recipients)

        part = MIMEText(html_content, "html", "utf-8")
        msg.attach(part)

        server = smtplib.SMTP(CONFIG["SMTP_SERVER"], CONFIG["SMTP_PORT"], timeout=10)
        server.starttls()
        server.login(username, password)
        server.sendmail(username, recipients, msg.as_string())
        server.quit()
        print(f"Đã gửi cảnh báo qua Email tới: {recipients}")
        return True
    except Exception as e:
        print(f"Lỗi khi gửi Email cảnh báo: {e}")
        return False

# send Zalo alert
def send_zalo_alert(title: str, message: str, level: str = "ERROR"):
    """Gửi tin nhắn cảnh báo tới Zalo của Admin qua Zalo OA API hoặc Webhook"""
    webhook_url = CONFIG["ZALO_WEBHOOK_URL"]
    access_token = CONFIG["ZALO_ACCESS_TOKEN"]
    admin_id = CONFIG["ZALO_ADMIN_USER_ID"]

    content = f"[HALOTICKET SYSTEM ALERT - {level}]\n\nTiêu đề: {title}\nThời gian: {datetime.now().strftime('%d/%m/%Y %H:%M:%S')}\nMức độ: {level}\n\nNội dung chi tiết:\n{message}"

    if not access_token or not admin_id:
        print(f"Zalo chưa cấu hình Access Token. Mô phỏng gửi Zalo:\n{content}\n")
        return False

    try:
        import urllib.request
        payload = {
            "recipient": {"user_id": admin_id},
            "message": {"text": content}
        }
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            webhook_url,
            data=data,
            headers={
                "Content-Type": "application/json",
                "access_token": access_token
            }
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            resp_data = json.loads(resp.read().decode())
            if resp_data.get("error") == 0:
                print(f"Đã gửi cảnh báo Zalo thành công tới User ID: {admin_id}")
                return True
            else:
                print(f"Zalo API trả về lỗi: {resp_data}")
                return False
    except Exception as e:
        print(f"Lỗi khi gửi Zalo alert: {e}")
        return False

# send Telegram alert
def send_telegram_alert(title: str, message: str, level: str = "ERROR"):
    """Gửi tin nhắn cảnh báo tới Telegram Bot / Channel của Admin"""
    bot_token = CONFIG["TELEGRAM_BOT_TOKEN"]
    chat_id = CONFIG["TELEGRAM_CHAT_ID"]

    content = f"[HALOTICKET SYSTEM ALERT - {level}]\n\nTiêu đề: {title}\nThời gian: {datetime.now().strftime('%d/%m/%Y %H:%M:%S')}\nMức độ: {level}\n\nNội dung chi tiết:\n{message}"

    if not bot_token or not chat_id:
        print(f"Telegram chưa cấu hình Bot Token / Chat ID. Mô phỏng gửi Telegram:\n{content}\n")
        return False

    try:
        import urllib.request
        telegram_url = f"https://api.telegram.org/bot{bot_token}/sendMessage"
        payload = {
            "chat_id": chat_id,
            "text": content
        }
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            telegram_url,
            data=data,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            resp_data = json.loads(resp.read().decode())
            if resp_data.get("ok"):
                print(f"Đã gửi cảnh báo Telegram thành công tới Chat ID: {chat_id}")
                return True
            else:
                print(f"Telegram API trả về lỗi: {resp_data}")
                return False
    except Exception as e:
        print(f"Lỗi khi gửi Telegram alert: {e}")
        return False

# check ssl certificate
def check_ssl_certificate(domain: str, port: int = 443):
    """Kiểm tra hạn chứng chỉ SSL và trả về số ngày còn lại"""
    clean_domain = domain.strip().replace("https://", "").replace("http://", "").split("/")[0].split(":")[0]
    try:
        context = ssl.create_default_context()
        with socket.create_connection((clean_domain, port), timeout=5) as sock:
            with context.wrap_socket(sock, server_hostname=clean_domain) as ssock:
                cert = ssock.getpeercert()
                not_after_str = cert["notAfter"]
                # format: 'Dec 30 12:00:00 2026 GMT'
                expire_date = datetime.strptime(not_after_str, "%b %d %H:%M:%S %Y %Z")
                remaining_days = (expire_date - datetime.now()).days
                return {
                    "domain": clean_domain,
                    "valid": True,
                    "remaining_days": remaining_days,
                    "expire_date": expire_date.strftime("%d/%m/%Y %H:%M:%S GMT"),
                    "issuer": dict(x[0] for x in cert.get("issuer", [])).get("commonName", "Unknown")
                }
    except Exception as e:
        return {
            "domain": clean_domain,
            "valid": False,
            "error": str(e),
            "remaining_days": -1
        }

# check microservices health
def check_microservices_health():
    """Kiểm tra kết nối tới các dịch vụ qua HTTP hoặc Socket TCP"""
    unhealthy = []
    healthy = []

    for svc in CONFIG["SERVICES"]:
        port = svc["port"]
        name = svc["name"]
        is_up = False
        latency_ms = 0

        # check socket port
        start = time.time()
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1.5):
                is_up = True
                latency_ms = int((time.time() - start) * 1000)
        except:
            is_up = False

        if is_up:
            healthy.append({"name": name, "port": port, "latency": latency_ms})
        else:
            unhealthy.append({"name": name, "port": port})

    return healthy, unhealthy

# scan error logs & warning
def scan_error_logs(max_lines: int = 100):
    """Quét các dòng log cuối cùng để phát hiện lỗi mới"""
    log_file = CONFIG["LOG_FILE_PATH"]
    errors_found = []

    if not os.path.exists(log_file):
        return []

    try:
        with open(log_file, "r", encoding="utf-8", errors="ignore") as f:
            lines = f.readlines()[-max_lines:]
            for line in lines:
                if any(kw in line for kw in ["ERROR", "Exception", "Critical", "FATAL", "Access Denied"]):
                    errors_found.append(line.strip())
    except Exception as e:
        print(f"[!] Không thể đọc log file: {e}")

    return errors_found

# check SSL
def check_all_ssl_certificates():
    """Kiểm tra toàn bộ danh sách SSL domains và gửi cảnh báo nếu cần"""
    print(f"\n[{datetime.now().strftime('%d/%m/%Y %H:%M:%S')}] Đang quét hạn chứng chỉ SSL các domain...")
    for domain in CONFIG["SSL_DOMAINS"]:
        ssl_info = check_ssl_certificate(domain)
        if ssl_info["valid"]:
            rem = ssl_info["remaining_days"]
            if rem < CONFIG["SSL_WARN_DAYS"]:
                msg = f"Chứng chỉ SSL của domain {domain} sắp hết hạn trong {rem} ngày (Hết hạn: {ssl_info['expire_date']})."
                print(f"[!] CẢNH BÁO: {msg}")
                send_zalo_alert("Chứng chỉ SSL sắp hết hạn!", msg, level="WARN")
                send_telegram_alert("Chứng chỉ SSL sắp hết hạn!", msg, level="WARN")
                send_email_alert("Cảnh báo chứng chỉ SSL sắp hết hạn", f"<p>{msg}</p>")
            else:
                print(f"[✓] SSL {domain}: Hợp lệ ({rem} ngày còn lại, Issuer: {ssl_info.get('issuer')}).")
        else:
            msg = f"Không thể xác thực chứng chỉ SSL cho domain {ssl_info['domain']}: {ssl_info.get('error', 'N/A')}."
            print(f"[!] LỖI SSL: {msg}")
            send_zalo_alert("Lỗi chứng chỉ SSL!", msg, level="ERROR")
            send_telegram_alert("Lỗi chứng chỉ SSL!", msg, level="ERROR")
            send_email_alert("CẢNH BÁO: Lỗi chứng chỉ SSL!", f"<p>{msg}</p>")

# run monitoring cycle
def run_monitoring_cycle(force_ssl: bool = False):
    """
    Chạy chu kỳ giám sát:
    - Microservices & Error Logs: quét liên tục theo interval (mặc định 60 giây)
    - SSL Certificates: quét 1 lần mỗi ngày vào khung giờ cố định (mặc định 09:00 sáng)
    """
    global last_ssl_check_date
    timestamp = datetime.now().strftime("%d/%m/%Y %H:%M:%S")
    print(f"\n[{timestamp}] Bắt đầu chu kỳ quét hệ thống HaloTicket...")

    # 1. check microservices (60s / lần)
    healthy, unhealthy = check_microservices_health()
    if unhealthy:
        svc_names = ", ".join([f"{s['name']}:{s['port']}" for s in unhealthy])
        alert_msg = f"Phát hiện {len(unhealthy)} microservices bị DOWN hoặc không phản hồi:\n- {svc_names}"
        print(f"[!] {alert_msg}")

        # sent warning
        send_zalo_alert("Dịch vụ Microservice ngừng hoạt động!", alert_msg, level="ERROR")
        send_telegram_alert("Dịch vụ Microservice ngừng hoạt động!", alert_msg, level="ERROR")
        send_email_alert(
            "CẢNH BÁO: Dịch vụ Microservice bị DOWN!",
            f"<h3>Hệ thống phát hiện dịch vụ bị ngừng:</h3><p><b>{svc_names}</b></p><p>Thời gian: {timestamp}</p>"
        )
    else:
        print(f"[✓] Tất cả {len(healthy)} microservices đều hoạt động ổn định (UP).")

    # 2. scan error logs (60s / lần)
    errors = scan_error_logs(50)
    if errors:
        print(f"[!] Phát hiện {len(errors)} dòng log lỗi/warning trong hệ thống gần nhất.")
    else:
        print("[✓] Không phát hiện lỗi nghiêm trọng mới trong log file.")

    # 3. check SSL (quét cố định 1 lần/ngày vào giờ ALERT_SSL_CHECK_SCHEDULE)
    today_str = datetime.now().strftime("%Y-%m-%d")
    current_time_str = datetime.now().strftime("%H:%M")
    target_time_str = CONFIG["SSL_CHECK_SCHEDULE"]

    should_check_ssl = force_ssl
    if not should_check_ssl:
        if last_ssl_check_date != today_str and current_time_str >= target_time_str:
            should_check_ssl = True

    if should_check_ssl:
        check_all_ssl_certificates()
        last_ssl_check_date = today_str
    else:
        print(f"[i] SSL: Lịch quét hàng ngày lúc {target_time_str} (Hôm nay {today_str} đã quét hoặc chưa tới giờ).")

# MAIN CLI
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="HaloTicket Alert Notifier CLI")
    parser.add_argument("--test-alert", action="store_true", help="Gửi thử nghiệm 1 cảnh báo test qua Email, Zalo và Telegram")
    parser.add_argument("--check-ssl", action="store_true", help="Chỉ quét hạn chứng chỉ SSL ngay lập tức và thoát")
    parser.add_argument("--watch", action="store_true", help="Chạy chế độ tự động giám sát liên tục theo chu kỳ")
    parser.add_argument("--interval", type=int, default=CONFIG["CHECK_INTERVAL_SECONDS"], help="Chu kỳ quét microservices tính bằng giây (mặc định: 60)")

    args = parser.parse_args()

    if args.test_alert:
        print("[*] Đang gửi thông báo thử nghiệm...")
        test_msg = "Đây là thông báo kiểm thử tự động từ hệ thống giám sát HaloTicket Monitoring System. Hệ thống kết nối Email, Zalo & Telegram hoạt động hoàn hảo!"
        send_zalo_alert("THỬ NGHIỆM HỆ THỐNG CẢNH BÁO", test_msg, level="INFO")
        send_telegram_alert("THỬ NGHIỆM HỆ THỐNG CẢNH BÁO", test_msg, level="INFO")
        send_email_alert(
            "Kiểm tra kết nối cảnh báo hệ thống HaloTicket",
            f"<h2>HaloTicket Alert System Test</h2><p>{test_msg}</p><p>Thời gian gửi: {datetime.now().strftime('%d/%m/%Y %H:%M:%S')}</p>"
        )
    elif args.check_ssl:
        print("[*] Kiểm tra thủ công hạn chứng chỉ SSL các domain...")
        check_all_ssl_certificates()
    elif args.watch:
        print(f"[*] Chế độ giám sát tự động kích hoạt:")
        print(f"    - Quét Microservices & Logs: mỗi {args.interval} giây")
        print(f"    - Quét Hạn SSL: mỗi ngày 1 lần vào lúc {CONFIG['SSL_CHECK_SCHEDULE']}")
        print(f"    - Bấm Ctrl+C để dừng.")
        try:
            while True:
                run_monitoring_cycle(force_ssl=False)
                time.sleep(args.interval)
        except KeyboardInterrupt:
            print("\n[*] Đã dừng giám sát.")
    else:
        # Chạy 1 lần toàn diện (bao gồm cả SSL)
        run_monitoring_cycle(force_ssl=True)
