-- The user's CPF, copied from their Keycloak account on their first access. No two users share
-- one; rows that have none yet (never logged in since, or deleted) stay NULL.
ALTER TABLE users ADD COLUMN IF NOT EXISTS cpf VARCHAR(11);

ALTER TABLE users ADD CONSTRAINT uq_users_cpf UNIQUE (cpf);
