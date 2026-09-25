package com.akpedia.server.repository;

import com.akpedia.server.entity.Embedding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmbeddingRepository extends JpaRepository<Embedding, Long> {

    List<Embedding> findByDocumentIdOrderByChunkIndex(Long documentId);

        /**
         * Returns the closest eligible chunk once per document, already ordered by relevance.
         *
         * <p>Eligible leaves out what cannot be opened afterwards: a document still being indexed
         * has no usable vectors yet, and an archived one is refused by
         * {@code GET /documents/{id}/file}, so listing it here would offer a result that leads to a
         * 403.
         */
        @Query(value = """
                        SELECT document_id, name, description, mime_type, matched_chunk, chunk_index, score
                        FROM (
                                SELECT d.id AS document_id,
                                             d.name,
                                             d.description,
                                             d.mime_type,
                                             e.content AS matched_chunk,
                                             e.chunk_index,
                                             1 - (e.vector <=> CAST(:queryVector AS vector)) AS score,
                                             ROW_NUMBER() OVER (
                                                     PARTITION BY d.id
                                                     ORDER BY e.vector <=> CAST(:queryVector AS vector)
                                             ) AS document_rank
                                FROM embeddings e
                                JOIN documents d ON d.id = e.document_id
                                WHERE d.processing_status = 'COMPLETED'
                                    AND d.status <> 'ARCHIVED'
                                    AND e.model_name = :modelName
                                    AND e.dimensions = :dimensions
                        ) matches
                        WHERE document_rank = 1
                            AND score >= :minimumScore
                        ORDER BY score DESC
                        LIMIT :resultLimit
                        """, nativeQuery = true)
        List<Object[]> search(
                        @Param("queryVector") String queryVector,
                        @Param("modelName") String modelName,
                        @Param("dimensions") int dimensions,
                        @Param("minimumScore") double minimumScore,
                        @Param("resultLimit") int resultLimit);

    void deleteByDocumentId(Long documentId);
}
