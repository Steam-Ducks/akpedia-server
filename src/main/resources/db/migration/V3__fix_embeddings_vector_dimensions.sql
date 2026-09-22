-- V2 pinned embeddings.vector at VECTOR(1536), a placeholder that never matched any real
-- model. akpedia-ml's pinned model (intfloat/multilingual-e5-small) produces 384-dimension
-- vectors, so an insert of a real embedding would be rejected by the column's own typmod.
--
-- Embeddings are derived data -- regenerated from the stored PDF, not source of truth -- so
-- existing rows are cleared rather than migrated, the same way a model change requires a
-- reindex per the akpedia-ml README.

TRUNCATE TABLE embeddings;

DROP INDEX ix_embeddings_vector;

ALTER TABLE embeddings
    ALTER COLUMN vector TYPE VECTOR(384);

CREATE INDEX ix_embeddings_vector
    ON embeddings USING hnsw (vector vector_cosine_ops);
