package com.akpedia.server.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akpedia.server.dto.DocumentChunk;
import com.akpedia.server.dto.EmbeddingModelInfo;
import com.akpedia.server.dto.IndexedDocumentRequest;
import com.akpedia.server.dto.ProcessingFailureRequest;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.DocumentNotApprovedException;
import com.akpedia.server.exception.DocumentNotFoundException;
import com.akpedia.server.exception.InvalidEmbeddingsException;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.EmbeddingRepository;

/**
 * Stores what the akpedia-ml worker reports about an approved document -- its embeddings, which
 * release it for search, or why it could not be indexed -- and then acknowledges the stream
 * message it came from.
 *
 * <p>The acknowledgement comes only after the commit. Acknowledged earlier, a rollback would lose
 * the result for good: the message would be gone and the document stuck in {@code PROCESSING}.
 * The other way round, a commit followed by a failed {@code XACK} leaves the message pending, and
 * the worker sends the same result again; that is why both calls are idempotent once the document
 * is {@code COMPLETED}.
 *
 * <p>A document that no longer exists is acknowledged too: there is nowhere to store the result,
 * and leaving the message pending would only have the worker try it again on every restart.
 */
@Service
public class DocumentIndexingService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentIndexingService.class);

    private final DocumentRepository documents;
    private final EmbeddingRepository embeddings;
    private final ProcessingQueue queue;
    private final TransactionTemplate transaction;

    public DocumentIndexingService(
            DocumentRepository documents,
            EmbeddingRepository embeddings,
            ProcessingQueue queue,
            PlatformTransactionManager transactionManager) {
        this.documents = documents;
        this.embeddings = embeddings;
        this.queue = queue;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Replaces the document's embeddings with the worker's and releases it for search.
     *
     * @param documentId the indexed document
     * @param result     the worker's chunks and vectors
     * @return {@code true} when stored, {@code false} when the document was already indexed and
     *         nothing was written
     * @throws InvalidEmbeddingsException  if a vector does not fit the index or a chunk has no text
     * @throws DocumentNotFoundException   if there is no such document
     * @throws DocumentNotApprovedException if the document is not approved
     */
    public boolean storeEmbeddings(Long documentId, IndexedDocumentRequest result) {
        checkFitsTheIndex(documentId, result);
        return thenAcknowledge(result.messageId(), () -> {
            Document document = lockedDocument(documentId);
            if (document.getProcessingStatus() == ProcessingStatus.COMPLETED) {
                LOG.info("Documento {} ja indexado; embeddings repetidos ignorados (mensagem {}).",
                        documentId, result.messageId());
                return false;
            }
            if (document.getStatus() != DocumentStatus.APPROVED) {
                throw new DocumentNotApprovedException(documentId, document.getStatus());
            }

            embeddings.deleteByDocumentId(documentId);
            EmbeddingModelInfo model = result.model();
            embeddings.saveAll(result.chunks().stream()
                    .map(chunk -> new Embedding(document, chunk.index(), chunk.text(), toVector(chunk),
                            model.name(), model.dimensions()))
                    .toList());

            OffsetDateTime now = OffsetDateTime.now();
            if (document.getApprovedAt() == null) {
                document.setApprovedAt(now);
            }
            document.setProcessingStatus(ProcessingStatus.COMPLETED);
            document.setProcessedAt(now);
            document.setProcessingError(null);
            LOG.info("Documento {} indexado: {} chunk(s) de {}.", documentId, result.chunks().size(), model.name());
            return true;
        });
    }

    /**
     * Records that the document could not be indexed, and why.
     *
     * <p>A document already indexed keeps its embeddings: a failure arriving after a success is a
     * stale report of a repeated message, not news about the document.
     *
     * @param documentId the document the worker gave up on
     * @param failure    the worker's reason
     * @return {@code true} when recorded, {@code false} when the document was already indexed
     * @throws DocumentNotFoundException if there is no such document
     */
    public boolean recordFailure(Long documentId, ProcessingFailureRequest failure) {
        return thenAcknowledge(failure.messageId(), () -> {
            Document document = lockedDocument(documentId);
            if (document.getProcessingStatus() == ProcessingStatus.COMPLETED) {
                LOG.info("Documento {} ja indexado; falha tardia ignorada (mensagem {}): {}",
                        documentId, failure.messageId(), failure.describe());
                return false;
            }
            document.setProcessingStatus(ProcessingStatus.FAILED);
            document.setProcessingError(failure.describe());
            LOG.warn("Indexacao do documento {} falhou: {}", documentId, failure.describe());
            return true;
        });
    }

    /** Runs {@code work} in its own transaction, then acknowledges the message once it commits. */
    private boolean thenAcknowledge(String messageId, Supplier<Boolean> work) {
        Boolean written;
        try {
            written = transaction.execute(status -> work.get());
        } catch (DocumentNotFoundException e) {
            queue.acknowledge(messageId);
            throw e;
        }
        queue.acknowledge(messageId);
        return Boolean.TRUE.equals(written);
    }

    private Document lockedDocument(Long documentId) {
        return documents.findForIndexing(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
    }

    /**
     * Refuses, before touching the database, a result the {@code VECTOR(384)} column would reject
     * halfway through the inserts.
     */
    private static void checkFitsTheIndex(Long documentId, IndexedDocumentRequest result) {
        EmbeddingModelInfo model = result.model();
        if (model.name() == null || model.name().isBlank()) {
            throw new InvalidEmbeddingsException(documentId, "model.name is blank.");
        }
        if (model.dimensions() != Embedding.VECTOR_DIMENSIONS) {
            throw new InvalidEmbeddingsException(documentId, "model.dimensions is %d, the index holds %d."
                    .formatted(model.dimensions(), Embedding.VECTOR_DIMENSIONS));
        }
        List<DocumentChunk> chunks = result.chunks();
        for (DocumentChunk chunk : chunks) {
            if (chunk.text() == null) {
                throw new InvalidEmbeddingsException(documentId, "chunk %d has no text.".formatted(chunk.index()));
            }
            if (chunk.embedding() == null || chunk.embedding().size() != Embedding.VECTOR_DIMENSIONS
                    || chunk.embedding().contains(null)) {
                throw new InvalidEmbeddingsException(documentId, "chunk %d does not have a vector of %d numbers."
                        .formatted(chunk.index(), Embedding.VECTOR_DIMENSIONS));
            }
        }
    }

    private static float[] toVector(DocumentChunk chunk) {
        List<Float> values = chunk.embedding();
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = values.get(i);
        }
        return vector;
    }
}
