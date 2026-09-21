-- Akpedia core schema: sectors, categories, permissions, users,
-- documents, chunks/embeddings and audit trail.
-- Dev and CI run the pgvector/pgvector:pg16 image, but the extension
-- still has to be enabled explicitly on each database.

CREATE EXTENSION IF NOT EXISTS vector;

----------------------------------------------------------------------
-- SECTORS
----------------------------------------------------------------------

CREATE TABLE sectors (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ
);

----------------------------------------------------------------------
-- CATEGORIES
----------------------------------------------------------------------

CREATE TABLE categories (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ
);

----------------------------------------------------------------------
-- PERMISSIONS (static catalog)
----------------------------------------------------------------------

CREATE TABLE permissions (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL
);

----------------------------------------------------------------------
-- CATEGORY PERMISSIONS
----------------------------------------------------------------------

CREATE TABLE category_permissions (
    category_id   BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    CONSTRAINT pk_category_permissions
        PRIMARY KEY (category_id, permission_id),
    CONSTRAINT fk_category_permissions_category_id
        FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE CASCADE,
    CONSTRAINT fk_category_permissions_permission_id
        FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
);

CREATE INDEX ix_category_permissions_permission_id
    ON category_permissions (permission_id);

----------------------------------------------------------------------
-- CATEGORIES AVAILABLE PER SECTOR
----------------------------------------------------------------------

CREATE TABLE sector_categories (
    sector_id   BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    CONSTRAINT pk_sector_categories
        PRIMARY KEY (sector_id, category_id),
    CONSTRAINT fk_sector_categories_sector_id
        FOREIGN KEY (sector_id) REFERENCES sectors (id) ON DELETE CASCADE,
    CONSTRAINT fk_sector_categories_category_id
        FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE CASCADE
);

CREATE INDEX ix_sector_categories_category_id
    ON sector_categories (category_id);

----------------------------------------------------------------------
-- USERS
----------------------------------------------------------------------

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(150) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    sector_id     BIGINT NOT NULL,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ,
    CONSTRAINT fk_users_sector_id
        FOREIGN KEY (sector_id) REFERENCES sectors (id) ON DELETE RESTRICT
);

CREATE INDEX ix_users_sector_id ON users (sector_id);

----------------------------------------------------------------------
-- DOCUMENTS (metadata; the binary lives in document_files)
----------------------------------------------------------------------

CREATE TABLE documents (
    id                BIGSERIAL PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    description       TEXT,
    mime_type         VARCHAR(100) NOT NULL,
    file_size         BIGINT NOT NULL,
    category_id       BIGINT NOT NULL,
    creator_id        BIGINT NOT NULL,
    approver_id       BIGINT,
    status            VARCHAR(30) NOT NULL,
    processing_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    processing_error  TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ,
    approved_at       TIMESTAMPTZ,
    processed_at      TIMESTAMPTZ,
    CONSTRAINT fk_documents_category_id
        FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_documents_creator_id
        FOREIGN KEY (creator_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_documents_approver_id
        FOREIGN KEY (approver_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_documents_file_size
        CHECK (file_size > 0),
    CONSTRAINT ck_documents_status
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'ARCHIVED')),
    CONSTRAINT ck_documents_processing_status
        CHECK (processing_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX ix_documents_category_id ON documents (category_id);
CREATE INDEX ix_documents_creator_id ON documents (creator_id);
CREATE INDEX ix_documents_approver_id ON documents (approver_id);
CREATE INDEX ix_documents_status ON documents (status);
CREATE INDEX ix_documents_processing_status ON documents (processing_status);

----------------------------------------------------------------------
-- DOCUMENT BINARY (1:1)
----------------------------------------------------------------------

CREATE TABLE document_files (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL UNIQUE,
    file_data   BYTEA NOT NULL,
    CONSTRAINT fk_document_files_document_id
        FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
);

----------------------------------------------------------------------
-- CHUNKS + EMBEDDINGS
----------------------------------------------------------------------

CREATE TABLE embeddings (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_index INTEGER NOT NULL,
    content     TEXT NOT NULL,
    vector      VECTOR(1536) NOT NULL,
    model_name  VARCHAR(150) NOT NULL,
    dimensions  INTEGER NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_embeddings_document_id_chunk_index
        UNIQUE (document_id, chunk_index),
    CONSTRAINT fk_embeddings_document_id
        FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT ck_embeddings_chunk_index
        CHECK (chunk_index >= 0)
);

-- Cosine similarity is the default for normalized text embeddings.
CREATE INDEX ix_embeddings_vector
    ON embeddings USING hnsw (vector vector_cosine_ops);

----------------------------------------------------------------------
-- AUDIT TRAIL
----------------------------------------------------------------------

CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT,
    action      VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    entity_id   BIGINT,
    details     JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- SET NULL preserves the audit trail when the user is removed.
    CONSTRAINT fk_audit_logs_user_id
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX ix_audit_logs_user_id ON audit_logs (user_id);
CREATE INDEX ix_audit_logs_created_at ON audit_logs (created_at);

----------------------------------------------------------------------
-- PERMISSION CATALOG
----------------------------------------------------------------------

INSERT INTO permissions (name, description) VALUES
    ('DOCUMENT_VIEW', 'Visualizar documentos da categoria'),
    ('DOCUMENT_CREATE', 'Criar documentos na categoria'),
    ('DOCUMENT_UPDATE', 'Editar documentos da categoria'),
    ('DOCUMENT_APPROVE', 'Aprovar ou rejeitar documentos da categoria'),
    ('DOCUMENT_DELETE', 'Remover documentos da categoria')
ON CONFLICT (name) DO NOTHING;
