-- A user cannot change their own e-mail or CPF: they ask, and an admin approves (the value is
-- applied in Keycloak) or rejects (with an optional note). The rows hold personal data, so they
-- are deleted with the account.
CREATE TABLE profile_change_requests (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    field         VARCHAR(10) NOT NULL,
    new_value     VARCHAR(255) NOT NULL,
    reason        VARCHAR(500),
    status        VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    decided_by_id BIGINT,
    decision_note VARCHAR(500),
    decided_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_profile_change_requests_user_id
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_profile_change_requests_decided_by_id
        FOREIGN KEY (decided_by_id) REFERENCES users (id),
    CONSTRAINT ck_profile_change_requests_field CHECK (field IN ('EMAIL', 'CPF')),
    CONSTRAINT ck_profile_change_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

-- One open request per user and field.
CREATE UNIQUE INDEX uq_profile_change_requests_pending
    ON profile_change_requests (user_id, field) WHERE status = 'PENDING';

-- The admin's queue, by status and age.
CREATE INDEX ix_profile_change_requests_status_created_at
    ON profile_change_requests (status, created_at);
