# Low-Cost VPS Production Deployment Guide

This guide describes how to deploy the **HaloTicket** backend stack on affordable Cloud VPS providers (such as Hetzner, Contabo, DigitalOcean, or Linode) with 2GB–4GB of RAM.

---

## 1. System Requirements & Recommendations

| Parameter | Minimum (with Swap) | Recommended |
|---|---|---|
| **CPU** | 2 vCPU | 2–4 vCPU |
| **RAM** | 2 GB (+ 4GB Swap) | 4 GB (+ 2GB Swap) |
| **Disk** | 25 GB SSD/NVMe | 40 GB NVMe |
| **OS** | Ubuntu 22.04 / 24.04 LTS | Ubuntu 24.04 LTS |
| **Docker** | Docker Engine 24+ & Compose v2 | Docker Engine 26+ |

---

## 2. Server Provisioning & Swap Configuration

On servers with 2GB–4GB RAM, configuring a Swap file is essential to prevent Out-Of-Memory (OOM) kills during container startup.

```bash
# 1. Allocate 4GB swapfile
sudo fallocate -l 4G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile

# 2. Persist swap across reboots
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# 3. Optimize swappiness for microservices (favor RAM, swap on demand)
sudo sysctl vm.swappiness=15
echo 'vm.swappiness=15' | sudo tee -a /etc/sysctl.conf
```

---

## 3. Pre-Configured Docker Optimizations

The unified `docker-compose.yml` includes production-ready optimizations:

1. **JVM Memory Tuning**:
   - Microservices use `-XX:+UseSerialGC` instead of G1GC, saving ~40-50MB per JVM.
   - `-Xss256k` reduces per-thread stack size.
   - `JAVA_TOOL_OPTIONS` explicitly caps Heap (`-Xms64m -Xmx192m` or `-Xmx256m`) and Metaspace (`-XX:MaxMetaspaceSize=128m`).
2. **Container Limits**:
   - Total memory reservation across all 13 containers is capped at **~3.55 GB**.
3. **Log Rotation**:
   - Every service is configured with `json-file` log driver capped at `3MB x 2 files` (max 78MB total disk).

---

## 4. Deployment Steps

### Step 1: Clone Repository & Setup Environment

```bash
git clone <repository_url> haloticket-backend
cd haloticket-backend

# Copy template and configure production secrets
cp .env.example .env
nano .env
```

Ensure you update:
- `POSTGRES_PASSWORD`: Strong random secret.
- `KEYCLOAK_ADMIN_PASSWORD`: Strong admin password.
- `JWT_SECRET`: Base64 256-bit key.
- `SMTP_USERNAME` and `SMTP_PASSWORD`: Production email provider (SendGrid, Mailgun, Amazon SES).

### Step 2: Launch Backend Stack

```bash
# Pull images and build custom microservice images
docker compose up -d --build

# Monitor health status of all 13 services
docker compose ps
```

### Step 3: Verify Status & Logs

```bash
# Check memory consumption per container:
docker stats --no-stream

# View logs for a specific service:
docker compose logs -f api-gateway
```

---

## 5. Reverse Proxy (Nginx + SSL)

To expose API Gateway (`8888`) and Keycloak (`9080`) securely over HTTPS on ports `80`/`443`:

```nginx
# Example Nginx configuration for API Gateway
server {
    listen 80;
    server_name api.haloticket.vn;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name api.haloticket.vn;

    ssl_certificate /etc/letsencrypt/live/api.haloticket.vn/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.haloticket.vn/privkey.pem;

    client_max_body_size 20M;

    location / {
        proxy_pass http://127.0.0.1:8888;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

---

## 6. Maintenance Commands

```bash
# Restart the stack:
docker compose restart

# Stop all containers (data is preserved in Docker volumes):
docker compose down

# Clean unused build cache to reclaim disk space:
docker system prune -f
```
