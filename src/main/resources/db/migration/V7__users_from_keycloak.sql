-- Users log in through Keycloak now. A row is linked to its Keycloak account by the account's
-- id (the token's sub claim), and is created on the user's first request to the API.

ALTER TABLE users ADD COLUMN keycloak_id VARCHAR(36);

ALTER TABLE users ADD CONSTRAINT uq_users_keycloak_id UNIQUE (keycloak_id);

-- Keycloak keeps the passwords, so new rows have none. The column stays, rather than being
-- dropped, so the seed in V5 still runs as written.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
