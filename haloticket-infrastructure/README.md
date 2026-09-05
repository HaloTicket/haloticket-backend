# HaloTicket Infrastructure & Observability Stack

This directory contains the complete **Docker-based infrastructure, Identity and Access Management (Keycloak IAM), and Observability tooling** for the **HaloTicket Microservices Platform**.

---

## Table of Contents
- [1. Overview](#1-overview)
- [2. Architecture & Service Breakdown](#2-architecture--service-breakdown)
- [3. Directory Structure](#3-directory-structure)
- [4. Getting Started](#4-getting-started)
- [5. Keycloak IAM Auto-Configuration](#5-keycloak-iam-auto-configuration)
- [6. Observability & Monitoring Tools](#6-observability--monitoring-tools)
- [7. API Testing with Postman](#7-api-testing-with-postman)
- [8. Useful Commands & Troubleshooting](#8-useful-commands--troubleshooting)
- [9. Production Deployment Guide](#9-production-deployment-guide)

---

## 1. Overview

To optimize developer machine resources (RAM/CPU) and follow microservices operational best practices, the infrastructure is split into **two independent Docker Compose configurations**:

1. **Core Infrastructure (`docker-compose.yml`)**: Contains mission-critical datastores and identity broker required to run and develop backend microservices (*PostgreSQL, Redis, Apache Kafka, Keycloak*).
2. **Observability Stack (`docker-observability.yml`)**: Contains web-based management GUIs (*pgAdmin 4, Kafka-UI, RedisInsight*) that can be started on demand when debugging.

---

## 2. Architecture & Service Breakdown

### Core Services (`docker-compose.yml`)

| Service | Container Name | Image | Host Port | Internal Port | Purpose |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **PostgreSQL** | `haloticket-postgres` | `postgres:16-alpine` | `5433` | `5432` | Relational database (initializes 8 microservice DBs) |
| **Redis** | `haloticket-redis` | `redis:7-alpine` | `6379` | `6379` | High-performance cache & distributed locking (AOF enabled) |
| **Apache Kafka** | `haloticket-kafka` | `apache/kafka:3.7.0` | `9092` | `9092` / `29092` | Event streaming broker (KRaft mode, ZooKeeper-less) |
| **Keycloak IAM** | `haloticket-keycloak` | `quay.io/keycloak/keycloak:24.0.4` | `9080` | `8080` | OpenID Connect / OAuth2 Identity Provider (Quarkus engine) |

### Observability Tools (`docker-observability.yml`)

| Tool | Container Name | Image | Host Port | Purpose | Pre-configured Credentials / Details |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **pgAdmin 4** | `haloticket-pgadmin` | `dpage/pgadmin4:latest` | `5050` | Web GUI for PostgreSQL | `admin@haloticket.com` / `admin`<br>*(Auto-loaded server: `HaloTicket Postgres`)* |
| **Kafka-UI** | `haloticket-kafka-ui` | `provectuslabs/kafka-ui:latest` | `9090` | Kafka Topic & Consumer Monitor | Cluster: `haloticket` via `kafka:29092` |
| **RedisInsight** | `haloticket-redis-insight` | `redis/redisinsight:latest` | `5540` | Redis Key, Memory & Pub/Sub GUI | Connect to host `redis`, port `6379` |

---

## 3. Directory Structure

```
haloticket-infrastructure/
├── docker-compose.yml            # Core services definition (Postgres, Redis, Kafka, Keycloak)
├── docker-observability.yml      # Observability tooling definition (pgAdmin, Kafka-UI, RedisInsight)
├── .env.example                  # Environment variables template with placeholder guidance
├── .env                          # Local environment variables (git-ignored)
├── postgres/
│   └── init/
│       └── 01-create-databases.sql # SQL script creating all 8 microservice databases
├── keycloak/
│   └── realm-export.json         # Complete Keycloak realm configuration for auto-import
├── pgadmin/
│   └── servers.json              # Pre-configured PostgreSQL connection for pgAdmin
├── postman/
│   └── HaloTicket-Keycloak.postman_collection.json # Ready-to-use API testing collection
└── README.md
```

---

## 4. Getting Started

### Prerequisites
- **Docker Desktop** (or Docker Engine 24+ & Docker Compose v2)
- **Git**

---

### Step 1: Prepare Environment Variables
Create your local `.env` configuration file by copying `.env.example`:

```bash
# On Windows (PowerShell)
copy .env.example .env

# On Linux / macOS
cp .env.example .env
```

Adjust the values in `.env` if you need custom passwords or custom host ports.

---

### Step 2: Start Core Infrastructure
From the `haloticket-infrastructure` folder (or backend root), run:

```bash
docker compose up -d
```

> **Note:** On the first startup, PostgreSQL will automatically execute `01-create-databases.sql` to initialize all databases. Keycloak will then wait for PostgreSQL to become healthy and automatically import the `haloticket-realm` configuration from `keycloak/realm-export.json`.

Verify that all containers are healthy:
```bash
docker ps
```

---

### Step 3: (Optional) Launch Observability Stack
When you need to inspect database tables, browse Kafka messages, or inspect Redis cache keys:

```bash
docker compose -f docker-observability.yml up -d
```

#### Accessing Observability Dashboards:
- **pgAdmin 4:** [http://localhost:5050](http://localhost:5050)
  - Login: `admin@haloticket.com` / `admin`
  - In the left sidebar, expand **Servers** -> **HaloTicket Postgres (Local)** to view all databases.
- **Kafka-UI:** [http://localhost:9090](http://localhost:9090)
  - View Kafka topics, consumer lag, and produce/consume test messages.
- **RedisInsight:** [http://localhost:5540](http://localhost:5540)
  - Connect to host: `redis`, port: `6379`, password: `secretpassword`.

To stop the observability tools and free up RAM:
```bash
docker compose -f docker-observability.yml down
```

---

## 5. Keycloak IAM Auto-Configuration

The Keycloak server automatically initializes with the **`haloticket-realm`** Realm. Zero manual clicks in the web console are required.

- **Admin Web Console:** [http://localhost:9080](http://localhost:9080) *(Username: `admin` / Password: `admin`)*
- **OIDC Discovery URL:** `http://localhost:9080/realms/haloticket-realm/.well-known/openid-configuration`
- **Token Endpoint:** `http://localhost:9080/realms/haloticket-realm/protocol/openid-connect/token`

### Pre-configured Clients

1. **`user-service`** *(Confidential Client)*:
   - Secret: `user-service-secret`
   - Service Accounts Enabled: `true` (equipped with `realm-management:manage-users` to manage users programmatically).
   - Direct Access Grants (Password grant): `true`.
2. **`haloticket-web`** *(Public Client)*:
   - Built for Next.js / React Web Frontend.
   - PKCE Challenge Method: `S256`.
   - Allowed Redirect URIs: `http://localhost:3000/*`, `http://localhost:5173/*`.
3. **`haloticket-mobile`** *(Public Client)*:
   - Built for Flutter / React Native mobile applications with PKCE `S256`.
   - Allowed Redirect URI: `haloticket://oauth/callback`.
4. **`api-gateway`** *(Resource Server Client)*:
   - Bearer-only token validation and claim forwarding.

### Pre-configured Realm Roles
- `ROLE_ADMIN`: Platform Administrator.
- `ROLE_ORGANIZER`: Event Organizer (create events, publish ticket categories).
- `ROLE_STAFF`: Event Staff / Ticket Inspector.
- `ROLE_USER`: Standard Customer (Default role assigned upon registration).

### Seeded Test Accounts
| Username | Password | Email | Assigned Roles |
| :--- | :--- | :--- | :--- |
| `admin` | `admin123` | `admin@haloticket.com` | `ROLE_ADMIN`, `ROLE_USER` |
| `organizer` | `organizer123` | `organizer@haloticket.com` | `ROLE_ORGANIZER`, `ROLE_USER` |
| `user` | `user123` | `user@haloticket.com` | `ROLE_USER` |

---

## 6. PostgreSQL Multi-Database Initialization

The initialization script [postgres/init/01-create-databases.sql](file:///d:/HaloTicket/HaloTicket_Repo/haloticket-backend/haloticket-infrastructure/postgres/init/01-create-databases.sql) automatically provisions individual isolated databases for each microservice:

1. `user_db`: User service, identity profile, and eKYC data.
2. `event_db`: Event catalogue, venue configurations, and schedules.
3. `ticket_db`: Ticket definitions, QR codes, and seat inventory.
4. `booking_db`: Order bookings, reservations, and hold locks.
5. `payment_db`: Payment transactions, ledger, and gateway logs.
6. `notification_db`: Notification templates, push logs, and email history.
7. `marketplace_db`: Secondary ticket resale marketplace.
8. `keycloak_db`: Keycloak IAM internal system data.

---

## 7. API Testing with Postman

A comprehensive Postman Collection is provided in:
[postman/HaloTicket-Keycloak.postman_collection.json](file:///d:/HaloTicket/HaloTicket_Repo/haloticket-backend/haloticket-infrastructure/postman/HaloTicket-Keycloak.postman_collection.json)

### Included Requests:
1. **0. OpenID Configuration (Discovery):** Fetch OIDC endpoints and public keys.
2. **1. Get Admin Access Token:** Authenticate master admin to call Keycloak Admin REST APIs.
3. **2. Customer Login (Direct Grant):** Authenticate `user / user123` and retrieve JWT Bearer token.
4. **3. Organizer Login (Direct Grant):** Authenticate `organizer / organizer123`.
5. **4. Refresh Access Token:** Exchange `refresh_token` for a renewed `access_token`.
6. **5. Get User Info:** Call `/protocol/openid-connect/userinfo` with access token.
7. **6. Admin - Create User:** Call Keycloak Admin REST API to provision a new user programmatically.

---

## 8. Useful Commands & Troubleshooting

### Stopping Services
```bash
# Stop core infrastructure
docker compose down

# Stop observability stack
docker compose -f docker-observability.yml down
```

### Complete Reset (Wipe All Databases & Start Fresh)
```bash
docker compose down -v
docker compose up -d
```

### Viewing Logs
```bash
# Tail Keycloak logs
docker logs -f haloticket-keycloak

# Tail PostgreSQL logs
docker logs -f haloticket-postgres

# Tail Kafka logs
docker logs -f haloticket-kafka
```

### Testing Token via CLI (PowerShell / CMD / Bash)

**Universal Single-Line Command (Works in PowerShell, CMD, Git Bash, macOS, Linux):**
```bash
curl.exe -X POST http://localhost:9080/realms/haloticket-realm/protocol/openid-connect/token -d "grant_type=password" -d "client_id=user-service" -d "client_secret=user-service-secret" -d "username=user" -d "password=user123"
```

**PowerShell Native Script:**
```powershell
$body = @{
    grant_type = 'password'
    client_id = 'user-service'
    client_secret = 'user-service-secret'
    username = 'user'
    password = 'user123'
}
Invoke-RestMethod -Uri "http://localhost:9080/realms/haloticket-realm/protocol/openid-connect/token" -Method POST -Body $body
```


---

## 9. Production Deployment Guide

When deploying to a remote Linux VPS (Ubuntu/Debian) or cloud infrastructure behind a reverse proxy (e.g., Nginx, Traefik, Caddy, Cloudflare, AWS ALB):

### 1. Configure `.env` for Production
```env
# Production PostgreSQL credentials
POSTGRES_USER=haloticket_prod
POSTGRES_PASSWORD=SuperStrongGeneratedPassword_123!
POSTGRES_PORT=5432

# Production Redis credentials
REDIS_PASSWORD=AnotherStrongPassword_456!
REDIS_PORT=6379

# Production Kafka broker IP / Domain
KAFKA_HOST=kafka.haloticket.com
KAFKA_PORT=9092

# Keycloak Production Domain & Strict SSL
KEYCLOAK_HOSTNAME=auth.haloticket.com
KEYCLOAK_HOSTNAME_STRICT=true
KC_PROXY_HEADERS=xforwarded
KEYCLOAK_ADMIN_PASSWORD=KeycloakSuperAdminPass_789!
```

### 2. Nginx Reverse Proxy Snippet (SSL Termination)
```nginx
server {
    listen 443 ssl http2;
    server_name auth.haloticket.com;

    ssl_certificate /etc/letsencrypt/live/auth.haloticket.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/auth.haloticket.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:9080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Port 443;
    }
}
```

### 3. Persistent Backups
- **PostgreSQL backup:**
  ```bash
  docker exec -t haloticket-postgres pg_dumpall -U haloticket > backup_$(date +%Y%m%d).sql
  ```
- **PostgreSQL restore:**
  ```bash
  cat backup_20260828.sql | docker exec -i haloticket-postgres psql -U haloticket
  ```
