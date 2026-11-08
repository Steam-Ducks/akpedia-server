-- The audit trail now also records every /api request (action HTTP_REQUEST) next to the named
-- events, and who did it, from where and with what: the user's Keycloak id and email are copied
-- in, so the entry still says who it was after the account changes, and also covers a user who
-- has no row in users yet.

ALTER TABLE audit_logs
    ADD COLUMN user_keycloak_id VARCHAR(36),
    ADD COLUMN user_email       VARCHAR(255),
    ADD COLUMN ip_address       VARCHAR(45),
    ADD COLUMN user_agent       VARCHAR(500),
    ADD COLUMN http_method      VARCHAR(10),
    ADD COLUMN path             VARCHAR(500),
    ADD COLUMN status_code      INTEGER,
    ADD COLUMN duration_ms      INTEGER;

-- The audit screen filters by kind of entry (events or requests) and by user, newest first.
CREATE INDEX ix_audit_logs_action_created_at ON audit_logs (action, created_at DESC);
CREATE INDEX ix_audit_logs_user_keycloak_id_created_at ON audit_logs (user_keycloak_id, created_at DESC);
