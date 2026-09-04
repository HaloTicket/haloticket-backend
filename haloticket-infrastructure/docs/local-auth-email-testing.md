# Local Auth Email Testing

The core infrastructure compose file includes Mailpit as a local-only SMTP sink for registration and password-reset OTP development.

Start it from `haloticket-infrastructure`:

```bash
docker compose up -d mailpit
```

- SMTP host: `localhost`
- SMTP port: `1025`
- Web inbox: [http://localhost:8025](http://localhost:8025)
- Authentication: none

The current compose file does not run `user-service`, so no application container environment is injected. A locally running sender should use the host settings above. If `user-service` is later added to the same compose network, use `mailpit:1025` instead of `localhost:1025`.

The application defaults to real Gmail SMTP. To use Mailpit locally, override the notification-service environment with `SMTP_HOST=localhost`, `SMTP_PORT=1025`, `SMTP_AUTH=false`, `SMTP_STARTTLS=false`, and `SMTP_FROM=no-reply@haloticket.local`. Without these overrides, configure a real SMTP provider and never commit its password.

The Mailpit container is a development sink and does not relay messages to real recipients. Keep production SMTP settings outside this compose file and provide them through the deployment environment.
