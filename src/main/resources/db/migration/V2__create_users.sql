-- V2: users table (phase 2 - users + security).
-- Owned by the users module; no other module writes to it.
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
