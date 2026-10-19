package com.akpedia.server.repository;

import com.akpedia.server.entity.Embedding;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmbeddingRepository extends JpaRepository<Embedding, Long> {

    List<Embedding> findByDocumentIdOrderByChunkIndex(Long documentId);

        /**
         * Returns the closest eligible chunk once per document, already ordered by relevance.
         *
         * <p>Eligible means approved and indexed. A document still being indexed has no usable
         * vectors yet; one awaiting approval or returned for a fix is not available to colleagues;
         * and an archived one is refused by {@code GET /documents/{id}/file}, so listing it here
         * would offer a result that leads to a 403.
         *
         * <p>The columns a result card shows besides the match -- category, the creator as the
         * person responsible, and when the document last changed -- come after the score, so the
         * positions of the original columns stay where {@code SearchService} reads them. A document
         * never edited has no {@code updated_at}, so its creation time stands in.
         *
         * <p>Only approved documents are searchable: one still under review, or rejected, holds
         * vectors but must not surface to anyone. The same rule guards
         * {@link #searchRecentByCategory} and {@link DocumentRepository#findRecent}; the three have
         * to move together.
         *
         * <p>{@code hasCategories} and {@code categoryIds} filter by category when the caller asked
         * for one or more; {@code categoryIds} still needs a value when there is no filter, since an
         * empty {@code IN ()} is invalid SQL, so the caller passes a placeholder the {@code OR}
         * never reaches.
         */
        @Query(value = """
                        SELECT document_id, name, description, mime_type, matched_chunk, chunk_index, score,
                               category, responsible_name, updated_at
                        FROM (
                                SELECT d.id AS document_id,
                                             d.name,
                                             d.description,
                                             d.mime_type,
                                             e.content AS matched_chunk,
                                             e.chunk_index,
                                             1 - (e.vector <=> CAST(:queryVector AS vector)) AS score,
                                             c.name AS category,
                                             u.name AS responsible_name,
                                             COALESCE(d.updated_at, d.created_at) AS updated_at,
                                             ROW_NUMBER() OVER (
                                                     PARTITION BY d.id
                                                     ORDER BY e.vector <=> CAST(:queryVector AS vector)
                                             ) AS document_rank
                                FROM embeddings e
                                JOIN documents d ON d.id = e.document_id
                                JOIN categories c ON c.id = d.category_id
                                JOIN users u ON u.id = d.creator_id
                                WHERE d.processing_status = 'COMPLETED'
                                    AND d.status = 'APPROVED'
                                    AND e.model_name = :modelName
                                    AND e.dimensions = :dimensions
                                    AND (:hasCategories = false OR d.category_id IN (:categoryIds))
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
                        @Param("hasCategories") boolean hasCategories,
                        @Param("categoryIds") Collection<Long> categoryIds,
                        @Param("resultLimit") int resultLimit);

        /**
         * The same eligible documents {@link #search} would consider, filtered by category only and
         * ordered by when they last changed -- for a query that asks for a category with no search
         * text. {@code score} always comes back null: with no query there is nothing to compare the
         * chunk against. The matched chunk is the document's first one, standing in as an excerpt.
         */
        @Query(value = """
                        SELECT document_id, name, description, mime_type, matched_chunk, chunk_index, score,
                               category, responsible_name, updated_at
                        FROM (
                                SELECT d.id AS document_id,
                                             d.name,
                                             d.description,
                                             d.mime_type,
                                             e.content AS matched_chunk,
                                             e.chunk_index,
                                             CAST(NULL AS double precision) AS score,
                                             c.name AS category,
                                             u.name AS responsible_name,
                                             COALESCE(d.updated_at, d.created_at) AS updated_at,
                                             ROW_NUMBER() OVER (
                                                     PARTITION BY d.id
                                                     ORDER BY e.chunk_index
                                             ) AS document_rank
                                FROM embeddings e
                                JOIN documents d ON d.id = e.document_id
                                JOIN categories c ON c.id = d.category_id
                                JOIN users u ON u.id = d.creator_id
                                WHERE d.processing_status = 'COMPLETED'
                                    AND d.status = 'APPROVED'
                                    AND (:hasCategories = false OR d.category_id IN (:categoryIds))
                        ) matches
                        WHERE document_rank = 1
                        ORDER BY updated_at DESC
                        LIMIT :resultLimit
                        """, nativeQuery = true)
        List<Object[]> searchRecentByCategory(
                        @Param("hasCategories") boolean hasCategories,
                        @Param("categoryIds") Collection<Long> categoryIds,
                        @Param("resultLimit") int resultLimit);

    /**
     * The text of the first chunk of each given document, as {@code [document_id, content]} rows:
     * the start of the document, which is what an excerpt of it shows. Should the document hold
     * vectors of more than one model, one of their first chunks is picked.
     */
    @Query(value = """
            SELECT DISTINCT ON (document_id) document_id, content
            FROM embeddings
            WHERE document_id IN (:documentIds)
            ORDER BY document_id, chunk_index, id
            """, nativeQuery = true)
    List<Object[]> findFirstChunks(@Param("documentIds") Collection<Long> documentIds);

    void deleteByDocumentId(Long documentId);
}
