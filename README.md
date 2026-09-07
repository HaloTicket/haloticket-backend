# HaloTicket Backend Microservices

Backend microservices and Docker infrastructure for the **HaloTicket** event ticketing platform.

---

## 🏛️ Architecture & Directory Structure

```bash
haloticket-backend/
├── api-gateway/               # Central API Entrypoint & Security Filter (Port 8888)
├── discovery-server/          # Netflix Eureka Service Registry (Port 8761)
├── user-service/              # Authentication, Profiles, OTP, Keycloak Broker (Port 8081)
├── event-service/             # Events, Categories, Search Analytics (Port 8082)
├── ticket-service/            # Ticket Inventory & Pricing Rules (Port 8083)
├── booking-service/           # Order Checkout & Ticket Reservation (Port 8084)
├── payment-service/           # Payment Processing & Gateway Integration (Port 8085)
├── notification-service/      # Email Delivery & Kafka OTP Consumer (Port 8086)
├── media-service/             # Image Upload & Asset Storage (Port 8087)
├── haloticket-infrastructure/ # PostgreSQL Init Scripts, Keycloak Realm & Themes
├── docker-compose.yml         # Single unified Compose specification for all 13 containers
├── Dockerfile                 # Multi-stage build using Eclipse Temurin 21 JRE Alpine
├── .env                       # Local environment secrets (ignored by Git)
├── .env.example               # Template environment configuration
└── README.md                  # Project documentation
```

### Components & Services

- **API Gateway** (Port `8888`) — Central reverse proxy and security filter routing all `/api/**` requests.
- **Discovery Server** (Port `8761`) — Netflix Eureka service registry for internal service discovery.
- **User Service** (Port `8081`) — User accounts, authentication, OTP challenge, and Keycloak synchronization.
- **Event Service** (Port `8082`) — Events, categories, organizers, and search analytics.
- **Ticket Service** (Port `8083`) — Ticket inventory, seating tiers, and pricing rules.
- **Booking Service** (Port `8084`) — Order checkout, ticket hold, and reservation workflows.
- **Payment Service** (Port `8085`) — Payment processing and transaction verification.
- **Notification Service** (Port `8086`) — Transactional emails and Kafka OTP consumer.
- **Media Service** (Port `8087`) — Image upload, asset storage, and S3 integration.
- **Keycloak IAM** (Port `9080`) — Identity provider, OAuth2/OIDC, Google login, and role management.
- **PostgreSQL 16** (Port `5433`) — Primary relational database.
- **Redis 7** (Port `6379`) — In-memory caching and distributed locking.
- **Apache Kafka** (Port `9092`) — KRaft event streaming and message broker.

---

## 🚀 Quick Start (All-in-One Docker)

Start the entire backend stack (all 13 containers) with a single command:

```bash
docker compose up -d
docker compose ps
```

Once all containers report `healthy`:
- **API Gateway**: `http://localhost:8888`
- **Eureka Dashboard**: `http://localhost:8761`
- **Keycloak IAM**: `http://localhost:9080`
- **Frontend App**: `http://localhost:5173` (run from `haloticket-web`)

To stop all containers:
```bash
docker compose down
```

---

## 💻 Terminal Development Mode

To run or debug microservices locally in your terminal or IDE while keeping database and messaging services in Docker:

### 1. Start Infrastructure Only
```bash
docker compose up -d postgres redis kafka keycloak
```

### 2. Stop Dockerized Microservices
If the full Docker stack is already running and you want to free up ports for terminal running:
```bash
docker compose stop discovery-server api-gateway user-service event-service ticket-service booking-service payment-service notification-service media-service
```

### 3. Run a Specific Service Locally
For example, to run `user-service` locally:
```bash
# Stop the Docker container for this service if running:
docker compose stop user-service

# Run with Maven wrapper:
cd user-service
./mvnw spring-boot:run
```
*(On Windows PowerShell, run `.\mvnw.cmd spring-boot:run`)*.

---

## 📋 Services & Ports Matrix

| Service | Host Port | Container Port | Protocol | Description |
|---|---:|---:|---|---|
| **API Gateway** | `8888` | `8888` | HTTP | Central gateway routing `/api/**` |
| **Discovery Server** | `8761` | `8761` | HTTP | Netflix Eureka Service Registry |
| **User Service** | `8081` | `8081` | HTTP | User profiles, password auth & OTP |
| **Event Service** | `8082` | `8082` | HTTP | Events, organizers & categories |
| **Ticket Service** | `8083` | `8083` | HTTP | Ticket inventory & tier pricing |
| **Booking Service** | `8084` | `8084` | HTTP | Order workflows & ticket reservation |
| **Payment Service** | `8085` | `8085` | HTTP | Payment status & webhooks |
| **Notification Service** | `8086` | `8086` | HTTP | Email notifications & Kafka consumers |
| **Media Service** | `8087` | `8087` | HTTP | Media upload & S3 storage handler |
| **Keycloak IAM** | `9080` | `8080` | HTTP | OAuth2/OIDC, Google login & RBAC |
| **PostgreSQL 16** | `5433` | `5432` | TCP | Relational database (multi-DB) |
| **Redis 7** | `6379` | `6379` | TCP | Distributed caching & locking |
| **Apache Kafka** | `9092` | `9092` | TCP | KRaft event broker & message queue |

---

## ⚙️ Configuration & Environment

Copy the template environment file:

```bash
cp .env.example .env
```
*(On Windows PowerShell, use `Copy-Item .env.example .env`)*.

Key environment variables in `.env`:
- `POSTGRES_USER` & `POSTGRES_PASSWORD`: Database credentials.
- `KEYCLOAK_ADMIN` & `KEYCLOAK_ADMIN_PASSWORD`: Keycloak administrator account.
- `GOOGLE_OAUTH_CLIENT_ID` & `GOOGLE_OAUTH_CLIENT_SECRET`: Credentials for Google OAuth2 login.
- `SMTP_USERNAME` & `SMTP_PASSWORD`: Mail credentials for OTP and transactional emails.

---

## 🌐 Low-Cost Server Deployment

The unified `docker-compose.yml` has been pre-configured with memory and disk optimizations:
- **JVM SerialGC (`-XX:+UseSerialGC`) & 256k thread stacks**: Reduces memory overhead by ~450MB.
- Total memory limit across all 13 containers is capped at **~3.55 GB**, allowing stable execution on a **4GB VPS** (or 2GB VPS with Swap).
- **Docker log rotation**: Capped at `3MB x 2 files` per container (max 78MB total).

For step-by-step production setup, reverse proxy configuration, and swap setup, refer to [DEPLOYMENT.md](DEPLOYMENT.md).

---

## 🧪 Testing

Run tests using the Maven wrapper:

```bash
# Run tests for a specific microservice:
cd user-service
./mvnw test

# Run tests for API gateway:
cd ../api-gateway
./mvnw test
```
