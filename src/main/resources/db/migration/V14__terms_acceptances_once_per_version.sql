-- A user accepts each version of the terms once. Accepting it again (a double click, two tabs)
-- answers the first acceptance; duplicates recorded before this rule keep only the first.

DELETE FROM terms_acceptances a
USING terms_acceptances b
WHERE a.user_id = b.user_id
  AND a.terms_version_id = b.terms_version_id
  AND (a.accepted_at > b.accepted_at OR (a.accepted_at = b.accepted_at AND a.id > b.id));

ALTER TABLE terms_acceptances
    ADD CONSTRAINT uq_terms_acceptances_user_version UNIQUE (user_id, terms_version_id);
