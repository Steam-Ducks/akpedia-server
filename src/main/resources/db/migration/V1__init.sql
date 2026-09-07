-- Exemplo mínimo de migration para validar o pipeline do Flyway:

CREATE TABLE IF NOT EXISTS app_info (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(100) NOT NULL UNIQUE,
    value       VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO app_info (key, value)
VALUES ('schema_version', 'V1')
ON CONFLICT (key) DO NOTHING;
