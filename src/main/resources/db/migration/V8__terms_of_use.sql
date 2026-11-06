-- Terms of use. Admins publish versions from the app; every user must accept the current one
-- before using the API. Both tables are append-only: a published version is never changed, so
-- each acceptance keeps pointing at the text the user actually saw.

CREATE TABLE terms_versions (
    id              BIGSERIAL PRIMARY KEY,
    version         INTEGER NOT NULL,
    title           VARCHAR(200) NOT NULL,
    content         TEXT NOT NULL,
    published_by_id BIGINT,
    published_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_terms_versions_version UNIQUE (version),
    CONSTRAINT fk_terms_versions_published_by_id
        FOREIGN KEY (published_by_id) REFERENCES users (id)
);

CREATE TABLE terms_acceptances (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    terms_version_id BIGINT NOT NULL,
    accepted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_terms_acceptances_user_id
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_terms_acceptances_terms_version_id
        FOREIGN KEY (terms_version_id) REFERENCES terms_versions (id)
);

-- The gate looks up each user's latest acceptance on every API request.
CREATE INDEX ix_terms_acceptances_user_id_accepted_at
    ON terms_acceptances (user_id, accepted_at DESC, id DESC);
