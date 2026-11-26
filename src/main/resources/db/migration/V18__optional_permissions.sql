-- Optional permissions (e.g. "E-mails sobre novidades") are part of the terms of use: each version
-- of the terms lists its own, and adding, changing or removing one means publishing a new version.
-- Unlike the terms themselves, they are optional: saying no never limits what the user can do.
--
-- [{"id", "label", "description"}]. Ids are never reused across versions: choices point at them.
ALTER TABLE terms_versions
    ADD COLUMN optional_permissions TEXT NOT NULL DEFAULT '[]';

-- Every choice a user made, for the version of the terms they saw. Append-only: the latest choice
-- per (user, version, permission) counts, and the rest is the record of who agreed to what, when.
CREATE TABLE optional_permission_choices (
    id               BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL,
    terms_version_id BIGINT NOT NULL,
    permission_id    VARCHAR(100) NOT NULL,
    granted          BOOLEAN NOT NULL,
    chosen_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_optional_permission_choices_user_id
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_optional_permission_choices_terms_version_id
        FOREIGN KEY (terms_version_id) REFERENCES terms_versions (id)
);

CREATE INDEX ix_optional_permission_choices_version_user
    ON optional_permission_choices (terms_version_id, user_id, chosen_at, id);
