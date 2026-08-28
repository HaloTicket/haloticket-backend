-- PostgreSQL Initialization Script for HaloTicket Microservices Platform
-- Executes on initial startup when postgres data directory is empty

CREATE DATABASE user_db;
CREATE DATABASE event_db;
CREATE DATABASE ticket_db;
CREATE DATABASE booking_db;
CREATE DATABASE payment_db;
CREATE DATABASE notification_db;
CREATE DATABASE marketplace_db;
CREATE DATABASE keycloak_db;

GRANT ALL PRIVILEGES ON DATABASE user_db TO haloticket;
GRANT ALL PRIVILEGES ON DATABASE event_db TO haloticket;
GRANT ALL PRIVILEGES ON DATABASE ticket_db TO haloticket;
GRANT ALL PRIVILEGES ON DATABASE booking_db TO haloticket;
GRANT ALL PRIVILEGES ON DATABASE payment_db TO haloticket;
GRANT ALL PRIVILEGES ON DATABASE notification_db TO haloticket;
GRANT ALL PRIVILEGES ON DATABASE marketplace_db TO haloticket;
GRANT ALL PRIVILEGES ON DATABASE keycloak_db TO haloticket;
