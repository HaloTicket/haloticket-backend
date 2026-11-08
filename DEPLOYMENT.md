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
   - Total memory reservation across the 13 original containers is capped at **~3.55 GB**. `ai-assistant-service` adds its own cap (`AI_ASSISTANT_MEMORY_LIMIT`, default `384m`); check free RAM on the VPS before enabling it.
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
- `REDIS_PASSWORD`: used by Redis and, for rate limiting, by ai-assistant (Redis database 1).
- AI variables (see section 5 and `.env.example`): `GEMINI_API_KEY`, `GROQ_API_KEY`, `OPENAI_API_KEY`, `AI_DB_USER`/`AI_DB_PASSWORD`, `CHATBOT_EVENT_DATABASE_URL`, `CHATBOT_TICKET_DATABASE_URL`, `AI_OPS_DATABASE_URL`.

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

## 5. AI Assistant Service (ai-assistant-service, port 8089)

The service is image-only: there is **no bind mount** of the source code any more, so what runs is exactly the image CI built. Kafka is no longer used by it.

### Environment variables

| Variable | Purpose | Required |
|---|---|---|
| `POSTGRES_USER`, `POSTGRES_PASSWORD` | compose builds the shared-account URLs (`DATABASE_URL`, `EVENT_DB_URL`, `TICKET_DB_URL`, `BOOKING_DB_URL`) from them; the password must have no URL-special characters (`@ / : ? #`) | required |
| `REDIS_PASSWORD` | compose builds `REDIS_URL` (`redis://:<pw>@redis:6379/1`) for rate limits and the search cache | required |
| `GEMINI_API_KEY`, `GROQ_API_KEY`, `OPENAI_API_KEY` | model providers (Groq first, then OpenAI or Gemini; Gemini also for embeddings) | at least one |
| `KEYCLOAK_*`, `AI_DB_USER`, `AI_DB_PASSWORD` | token validation; read-only role `ai_readonly` for data analysis | as before |
| `CHATBOT_EVENT_DATABASE_URL`, `CHATBOT_TICKET_DATABASE_URL` | full URLs of role `ai_chatbot_ro` on event_db / ticket_db | required for the customer chatbot (else 503 `AI_DB_NOT_CONFIGURED`) |
| `AI_OPS_DATABASE_URL` | full URL of role `ai_writer` on ai_ops | optional (empty = metering off) |
| `AI_ADMIN_DATABASE_URL` | full URL of role `ai_admin_rw` on ai_ops (admin intelligence: stored risk scores, decisions, pinned reports, dashboard summary) | optional (empty = scores not stored; pins/summary cache/decisions answer 503 `AI_DB_NOT_CONFIGURED`) |
| `INTERNAL_API_BASE_URL` | gateway address ai-assistant uses to read events, users, reconciliation and resale with the admin's own token; default `http://api-gateway:8888` | optional |
| `RISK_*`, `ATTN_*`, `AI_RL_DASHBOARD_SUMMARY_PER_DAY`, `AI_MAX_TOKENS_DASHBOARD_SUMMARY` | risk level thresholds and "needs attention" thresholds; defaults in `docker-compose.yml` | optional |
| `AI_ASSISTANT_MEMORY_LIMIT` | container memory cap, default `384m` | optional |
| `AI_LLM_DEADLINE_SECONDS`, `AI_DAILY_CALL_BUDGET`, `AI_SEARCH_CACHE_SECONDS`, `AI_TRUSTED_PROXY_CIDRS`, `AI_RL_*`, `AI_MAX_TOKENS_*` | cost control and rate limits; defaults in `docker-compose.yml` and `.env.example` | optional |

### First deploy order

1. Run the four SQL files by hand, in order (they are never run automatically; each takes `-v ai_password='...'` where noted):
   1. `haloticket-infrastructure/postgres/ai-ops/01-create-ai-ops-db.sql` on database `postgres`
   2. `.../ai-ops/02-ai-ops-schema.sql` on `ai_ops` (creates `ai_writer`)
   3. `.../ai-chatbot/01-ai-chatbot-ro-event-db.sql` on `event_db` (creates `ai_chatbot_ro`)
   4. `.../ai-chatbot/02-ai-chatbot-ro-ticket-db.sql` on `ticket_db` (same password as step 3)
   5. (admin intelligence, after step 2) `.../ai-admin/01-ai-admin-schema.sql` on `ai_ops`: `psql -U <owner> -d ai_ops -v ON_ERROR_STOP=1 -v ai_password='<new password>' -f 01-ai-admin-schema.sql` (creates the four tables and the role `ai_admin_rw`; the last SELECT in the file verifies the grants). Then set `AI_ADMIN_DATABASE_URL` in `.env`.
2. Set the environment variables in `.env` (the table above).
3. Deploy the backend (CI builds the image; see below), then deploy the web app.

### Updating ai-assistant

CI builds and pushes the image. On the VPS:

```bash
git pull origin develop
docker compose pull ai-assistant-service && docker compose up -d --no-deps ai-assistant-service
```

Environment variables and compose changes are only read when the container is recreated, so a change to `.env` needs the same `up -d --no-deps` command.

### Reverse proxy and client IP

The VPS uses Caddy v2: `reverse_proxy localhost:8888` with `header_up X-Forwarded-For {remote_host}`. ai-assistant takes the client IP from `X-Forwarded-For` (left-most value) only when the direct peer is inside `AI_TRUSTED_PROXY_CIDRS`; keep the gateway and ai-assistant ports bound to `127.0.0.1` so a client cannot reach them directly and forge the header.

### System monitoring data (CPU, memory, uptime, logs)

Every Spring service now runs Actuator on its own port 9091 (only `health` and `metrics`; the port is **not published**, the gateway
refuses `/actuator/**` on its public port 8888) and writes its log to `/logs/<service>.log` in the shared volume
`haloticket-service-logs` (rotated, 5 MB x 2). ai-assistant reads both inside the compose network. Nothing to configure: rebuild and
redeploy **all** Java services (new `spring-boot-starter-actuator` dependency, `/logs` in the image) and `ai-assistant-service`.
A service that was not rebuilt shows "no data" for CPU/memory/uptime; it never shows made-up numbers.
Check from the server: `docker run --rm --network haloticket-network curlimages/curl -s http://ticket-service:9091/actuator/metrics/process.uptime`.

### Post-deploy checks

```bash
# 1. the container runs from the image: no bind mount (expect empty output)
docker inspect haloticket-ai-assistant --format '{{range .Mounts}}{{.Type}} {{.Source}}{{"
"}}{{end}}'
# 2. healthy (process check only) and /health answers
docker compose ps ai-assistant-service
curl -s http://localhost:8089/health
# 3. rate limit: an empty body never reaches a model but still counts toward the limit
#    expect 422 six times, then 429
for i in 1 2 3 4 5 6 7; do curl -s -o /dev/null -w '%{http_code} ' -X POST http://localhost:8888/api/customer/smart-booking-chat -H 'Content-Type: application/json' -d '{}'; done
# 4. logs: no startup errors, a warning only if AI_OPS_DATABASE_URL is empty
docker compose logs --tail 50 ai-assistant-service
```

### Rollback

1. `git revert` the change on `develop` and push; CI rebuilds the previous code. Or redeploy a previous image tag: set `IMAGE_TAG=<previous sha>` and run `docker compose pull ai-assistant-service && docker compose up -d --no-deps ai-assistant-service`.
2. The new variables are harmless to the old image; the SQL roles and the `ai_ops` database can stay (each file has a commented ROLLBACK block).
3. If the chatbot is unavailable (503), the web app falls back to plain keyword search; no other service depends on ai-assistant.

---

## 6. Reverse Proxy (Caddy on the VPS; Nginx example)

The VPS runs Caddy v2 in front of the gateway:

```caddyfile
api.example.com {
    reverse_proxy localhost:8888 {
        header_up X-Forwarded-For {remote_host}
    }
}
```

Equivalent Nginx example:

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

## 7. Maintenance Commands

```bash
# Restart the stack:
docker compose restart

# Stop all containers (data is preserved in Docker volumes):
docker compose down

# Clean unused build cache to reclaim disk space:
docker system prune -f
```
