# HaloTicket Backend

## Google OAuth2 through Keycloak

1. Create an OAuth 2.0 Web application in Google Cloud Console.
2. Add this authorized redirect URI for local development:
   `http://localhost:9080/realms/haloticket-realm/broker/google/endpoint`
3. Copy `.env.example` to `.env` and set `GOOGLE_OAUTH_CLIENT_ID` and
   `GOOGLE_OAUTH_CLIENT_SECRET`.
4. Start the stack with `docker compose up -d`.

The realm import configures Google as the `google` identity provider. New
Google users are created with `ROLE_USER`. If a Google email already belongs
to a password account, Keycloak's `first broker login` flow requires the user
to authenticate that existing account before linking it.

Keycloak skips startup realm imports when `haloticket-realm` already exists.
For an existing local database, either add the Google provider in Keycloak
Admin Console with the same settings or recreate only the local Keycloak realm
before starting the stack again. Do not remove shared PostgreSQL data unless
you intend to reset all local services.
