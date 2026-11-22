-- A user who deletes their account keeps an anonymized row (their documents and terms
-- acceptances still point at it); deleted_at says when, and that the row is no longer anyone's.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
