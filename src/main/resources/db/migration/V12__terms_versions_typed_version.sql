-- The admin now types the version of the terms ("1.0", "2.1", "2026.1") instead of the server
-- numbering them 1, 2, 3. The versions already published keep their number, as text.
--
-- The current terms are the newest published (published_at, then id), never the "highest"
-- version: versions are labels, and "10" < "9" as text.

ALTER TABLE terms_versions
    ALTER COLUMN version TYPE VARCHAR(20) USING version::text;

-- uq_terms_versions_version stays. Besides it, a version may not repeat in another case
-- either: "v1" after "V1" is refused by the server and, for a race, by this index.
CREATE UNIQUE INDEX uq_terms_versions_version_lower ON terms_versions (lower(version));

-- The current terms are looked up on every API request (the terms gate).
CREATE INDEX ix_terms_versions_published_at ON terms_versions (published_at DESC, id DESC);
