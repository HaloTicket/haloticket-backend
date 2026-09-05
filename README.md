# HaloTicket

Backend microservices and local infrastructure for the HaloTicket ticketing platform. The frontend lives in the sibling `haloticket-web` repository.

## Architecture

```text
haloticket-backend/
├── api-gateway/               # API entry point
├── discovery-server/          # Eureka service registry
├── user-service/              # Users, password auth, OTP, Keycloak broker login
├── event-service/             # Events and search
├── ticket-service/            # Tickets
├── booking-service/           # Booking and checkout workflow
├── payment-service/           # Payment integration
├── notification-service/      # Notifications and Kafka OTP email consumer
├── haloticket-infrastructure/ # PostgreSQL, Redis, Kafka, Keycloak, observability
└── docs/                      # Stable project documentation
```

All Java services use Spring Boot 4, Java 21, Maven, PostgreSQL, Kafka and Eureka. Service package layout follows `config`, `controller`, `dto`, `entity`, `enums`, `repository`, `service`, and `service/impl`.

## Local prerequisites

- Docker Desktop with Docker Compose
- Java 21
- Node.js 20+ and npm (for `haloticket-web`)
- A Google Cloud OAuth 2.0 Web client for Google login

## Configuration

```powershell
Copy-Item .env.example .env
```

Set the local secrets in `.env`, especially:

- `KEYCLOAK_ADMIN` and `KEYCLOAK_ADMIN_PASSWORD`
- PostgreSQL credentials
- `GOOGLE_OAUTH_CLIENT_ID` and `GOOGLE_OAUTH_CLIENT_SECRET`
- SMTP credentials used by `notification-service`

Never commit `.env` or real credentials.

## Start infrastructure

From this directory:

```powershell
docker compose up -d
docker compose ps
```

The core stack starts PostgreSQL, Redis, Kafka and Keycloak. Optional admin tools are in `haloticket-infrastructure/docker-observability.yml`:

```powershell
docker compose -f haloticket-infrastructure/docker-observability.yml up -d
```

There is no Mailpit container in the core stack. OTP email is sent by `notification-service` through the SMTP settings in `.env` after the OTP event is published to Kafka.

## Start backend services

Start discovery first, then the gateway and application services. Each service can be started from its own directory:

```powershell
cd discovery-server; cmd /c mvnw.cmd spring-boot:run
cd ..\api-gateway; cmd /c mvnw.cmd spring-boot:run
cd ..\user-service; cmd /c mvnw.cmd spring-boot:run
```

Repeat for `event-service`, `ticket-service`, `booking-service`, `payment-service` and `notification-service` as needed.

| Service | Port |
|---|---:|
| Discovery Server | 8761 |
| API Gateway | 8888 |
| User Service | 8081 |
| Event Service | 8082 |
| Ticket Service | 8083 |
| Booking Service | 8084 |
| Payment Service | 8085 |
| Notification Service | 8086 |
| Keycloak | 9080 |

The frontend runs on `http://localhost:5173` and calls the gateway at `http://localhost:8888`.

## Google OAuth2 with Keycloak

Create a Google OAuth 2.0 Web client and add this authorized redirect URI:

```text
http://localhost:9080/realms/haloticket-realm/broker/google/endpoint
```

The imported realm configures Google as the `google` identity provider. The frontend opens Google in a centered popup for login, quick registration, and account linking. Cancelling the Google consent flow closes the popup and returns the button to its idle state.

Existing password accounts must re-authenticate in Keycloak before their Google account is linked. New Google accounts are created with the normal user role.

If the realm already exists in the local Keycloak database, startup import is skipped. Update the provider in the Keycloak Admin Console or recreate only the local Keycloak realm when changing Google credentials.

## OTP delivery

Registration and password recovery follow this flow:

```text
user-service → Kafka topic haloticket.auth.otp.v1 → notification-service → SMTP
```

`user-service` waits for Kafka producer acknowledgement before returning the OTP challenge. The frontend only shows the OTP screen after that API call succeeds.

## Tests and builds

```powershell
# User service
cd user-service
cmd /c mvnw.cmd test

# Any other service
cmd /c mvnw.cmd test
```

For the frontend:

```powershell
cd ..\haloticket-web
npm install
npm test -- --run
npm run build
```

## Useful endpoints

- Gateway health: `http://localhost:8888/actuator/health`
- Keycloak: `http://localhost:9080`
- Frontend: `http://localhost:5173`

Use the gateway for application API calls; call individual service ports only for local development and diagnostics.

