-- Review flow: the approver's comment when a document is returned, and the category
-- the system suggested together with how confident it is in that suggestion.

ALTER TABLE documents
    ADD COLUMN review_comment        TEXT,
    ADD COLUMN reviewed_at           TIMESTAMPTZ,
    ADD COLUMN suggested_category_id BIGINT,
    ADD COLUMN category_confidence   NUMERIC(4,3),
    -- SET NULL: the suggestion is advisory, so removing a category must not block
    -- on (or take down) the documents it was once suggested for.
    ADD CONSTRAINT fk_documents_suggested_category_id
        FOREIGN KEY (suggested_category_id) REFERENCES categories (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_documents_category_confidence
        CHECK (category_confidence BETWEEN 0 AND 1);

CREATE INDEX ix_documents_suggested_category_id ON documents (suggested_category_id);

----------------------------------------------------------------------
-- CATEGORY CATALOG
----------------------------------------------------------------------

INSERT INTO categories (name, description) VALUES
    ('Tecnico', 'Documentos tecnicos'),
    ('Regulatorio', 'Documentos regulatorios'),
    ('Juridico', 'Documentos juridicos'),
    ('Qualidade', 'Documentos de qualidade')
ON CONFLICT (name) DO NOTHING;
