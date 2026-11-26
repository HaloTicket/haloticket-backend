-- PostgreSQL Initialization Script for HaloTicket Microservices Platform
-- Executes on initial startup when postgres data directory is empty

CREATE DATABASE user_db;
CREATE DATABASE event_db;
CREATE DATABASE ticket_db;
CREATE DATABASE booking_db;
CREATE DATABASE payment_db;
CREATE DATABASE marketplace_db;
CREATE DATABASE keycloak_db;
-- The PostgreSQL entrypoint runs this script as POSTGRES_USER. That user owns
-- every database it creates, so no grant to a hard-coded role is required.
