package com.akpedia.server.service;

import java.time.OffsetDateTime;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.akpedia.server.config.ProcessingProperties;
import com.akpedia.server.repository.DocumentRepository;

/**
 * Hands an approved document to the indexing worker, by adding its id to a Redis Stream, and
 * records on the document that it did.
 *
 * <p>Sent only after the approval commits: sent any earlier, a rolled-back approval would still
 * have the worker index a document that is back in {@code PENDING_APPROVAL}, and one whose
 * transaction is still open might be picked up before the worker can see it approved.
 *
 * <p>Sending after the commit leaves a gap the other way round: the approval is saved, and the
 * process dies or Redis is down before the id is sent. That is what
 * {@code processing_enqueued_at} is for -- it is only set once Redis has accepted the id, so a
 * document approved but never enqueued stays findable, and {@link ProcessingRetry} sends it
 * again. The documents table is, in effect, the outbox for the stream.
 *
 * <p>The worker never acknowledges a message itself: it calls the server back, and the server
 * acknowledges it with {@link #acknowledge} once the result is committed.
 *
 * <p>The price is that a document can be sent twice: if the process dies after Redis accepted the
 * id but before the send was recorded, the retry sends it again. The worker has to treat a
 * repeated id as a no-op.
 */
@Component
public class ProcessingQueue {

    /** Field of the stream entry that carries the document id. */
    static final String DOCUMENT_ID_FIELD = "documentId";

    private static final Logger LOG = LoggerFactory.getLogger(ProcessingQueue.class);

    private final StringRedisTemplate redis;
    private final ProcessingProperties properties;
    private final DocumentRepository documents;
    private final TransactionTemplate ownTransaction;

    public ProcessingQueue(
            StringRedisTemplate redis,
            ProcessingProperties properties,
            DocumentRepository documents,
            PlatformTransactionManager transactionManager) {
        this.redis = redis;
        this.properties = properties;
        this.documents = documents;
        // Its own transaction: after a commit there is none left to join, and the retry runs
        // outside any.
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApproved(DocumentApprovedEvent event) {
        publish(event.documentId());
    }

    /**
     * Adds the document's id to the stream and records that it was sent.
     *
     * <p>Never throws: called right after an approval commits, anything thrown would reach the
     * approver as an error for a decision that was saved. A document that could not be sent is
     * left as it was, for {@link ProcessingRetry} to find.
     *
     * @param documentId the approved document
     * @return whether Redis accepted the id; {@code false} means it is still waiting to be sent
     */
    public boolean publish(Long documentId) {
        try {
            redis.opsForStream().add(StreamRecords.newRecord()
                    .ofMap(Map.of(DOCUMENT_ID_FIELD, documentId.toString()))
                    .withStreamKey(properties.stream()));
        } catch (DataAccessException e) {
            LOG.warn("Documento {} aprovado, mas nao foi possivel publica-lo em {}; sera reenviado.",
                    documentId, properties.stream(), e);
            return false;
        }

        try {
            ownTransaction.executeWithoutResult(status -> documents.markEnqueued(documentId, OffsetDateTime.now()));
        } catch (RuntimeException e) {
            // Sent, but not recorded: the retry will send it once more, which the worker tolerates.
            LOG.warn("Documento {} publicado em {}, mas o envio nao foi registrado; sera reenviado.",
                    documentId, properties.stream(), e);
        }
        return true;
    }

    /**
     * Acknowledges ({@code XACK}) a message the worker has finished with, taking it off the
     * group's pending list.
     *
     * <p>Called once the worker's result, or its failure, is committed. Never throws: by then the
     * outcome is saved, and a message left unacknowledged only means the worker sends the same
     * result again after a restart, which is answered without rewriting anything.
     *
     * @param messageId id of the stream entry
     */
    public void acknowledge(String messageId) {
        try {
            redis.opsForStream().acknowledge(properties.stream(), properties.group(), messageId);
        } catch (DataAccessException e) {
            LOG.warn("Nao foi possivel confirmar (XACK) a mensagem {} em {}; ela continua pendente.",
                    messageId, properties.stream(), e);
        }
    }
}
