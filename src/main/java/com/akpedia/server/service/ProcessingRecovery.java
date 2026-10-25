package com.akpedia.server.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import com.akpedia.server.config.ProcessingProperties;
import com.akpedia.server.dto.ProcessingFailureRequest;
import com.akpedia.server.exception.DocumentNotFoundException;

/**
 * Takes over the stream messages a dead worker leaves pending forever: a worker that stops mid
 * document never acknowledges its message, and if it never comes back, nothing but this job will
 * ever look at it again -- a worker only resumes the pending entries of its own consumer name, on
 * its own restart (see {@code IndexingWorker._resume_pending} on the akpedia-ml side).
 *
 * <p>Redis's own recovery command for this is {@code XAUTOCLAIM}, but the Spring Data Redis
 * version this server runs on (3.3) does not expose it. The same outcome is reached with the two
 * commands it is built from: {@code XPENDING} lists what is pending, with how long it has been
 * idle and how many times it was delivered; {@code XCLAIM} takes one entry over by id. Every
 * message past {@link ProcessingProperties#recoveryIdle()} without a delivery is handled this
 * way, every {@link ProcessingProperties#recoveryInterval()}.
 *
 * <p>A message within the limit is claimed under this job's own consumer name -- just so it is no
 * longer attributed to the dead one -- and immediately handed out again as a brand new message,
 * the same way {@link ProcessingQueue} sends one after an approval; the claimed entry is then
 * acknowledged, since the new message is what carries the document onward. A message already
 * delivered {@link ProcessingProperties#maxDeliveries()} times is given up on instead: the
 * document is marked {@code FAILED} and the message acknowledged, exactly as
 * {@link DocumentIndexingService#recordFailure} already does for a failure the worker reports
 * itself. The limit mirrors the worker's own, so a document is not retried more for having been
 * abandoned than for having kept failing.
 */
@Component
public class ProcessingRecovery implements SchedulingConfigurer {

    /** Consumer name this job claims stuck messages under; it never reads them itself. */
    static final String RECOVERY_CONSUMER = "recovery";

    private static final Logger LOG = LoggerFactory.getLogger(ProcessingRecovery.class);
    private static final String GIVE_UP_CODE = "retry_limit_exceeded";
    private static final String GIVE_UP_MESSAGE = "Retry limit exceeded";

    private final StringRedisTemplate redis;
    private final ProcessingQueue queue;
    private final DocumentIndexingService indexingService;
    private final ProcessingProperties properties;

    public ProcessingRecovery(
            StringRedisTemplate redis,
            ProcessingQueue queue,
            DocumentIndexingService indexingService,
            ProcessingProperties properties) {
        this.redis = redis;
        this.queue = queue;
        this.indexingService = indexingService;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(
                new FixedDelayTask(this::recoverDue, properties.recoveryInterval(), properties.recoveryInterval()));
    }

    /** Runs one recovery pass. */
    void recoverDue() {
        recover();
    }

    /**
     * Looks at up to {@link ProcessingProperties#recoveryBatchSize()} pending messages, and
     * redelivers or gives up on every one idle past {@link ProcessingProperties#recoveryIdle()}.
     *
     * @return how many messages were handled
     */
    int recover() {
        PendingMessages pending;
        try {
            pending = redis.opsForStream()
                    .pending(properties.stream(), properties.group(), Range.unbounded(), properties.recoveryBatchSize());
        } catch (DataAccessException e) {
            LOG.warn("Nao foi possivel consultar mensagens pendentes em {}; tentando na proxima rodada.",
                    properties.stream(), e);
            return 0;
        }

        int handled = 0;
        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery().compareTo(properties.recoveryIdle()) < 0) {
                continue;
            }
            String messageId = message.getIdAsString();
            if (message.getTotalDeliveryCount() >= properties.maxDeliveries()) {
                giveUp(messageId);
            } else {
                redeliver(messageId);
            }
            handled++;
        }
        if (handled > 0) {
            LOG.info("Recuperacao de mensagens paradas em {}: {} mensagem(ns) tratada(s).",
                    properties.stream(), handled);
        }
        return handled;
    }

    /**
     * Claims the stuck message, publishes its document as a new message, and acknowledges the
     * claimed one: from here on the new message is the one tracked, with its own fresh delivery
     * count.
     */
    private void redeliver(String messageId) {
        List<MapRecord<String, String, String>> claimed;
        try {
            claimed = streamOps().claim(properties.stream(), properties.group(), RECOVERY_CONSUMER,
                    XClaimOptions.minIdle(properties.recoveryIdle()).ids(messageId));
        } catch (DataAccessException e) {
            LOG.warn("Nao foi possivel reivindicar (XCLAIM) a mensagem {} em {}; tentando na proxima rodada.",
                    messageId, properties.stream(), e);
            return;
        }
        if (claimed.isEmpty()) {
            // Claimed, acknowledged or trimmed by something else between the scan and this call.
            return;
        }

        Long documentId = documentIdOf(claimed.get(0));
        if (documentId == null) {
            queue.acknowledge(messageId);
            return;
        }
        queue.publish(documentId);
        queue.acknowledge(messageId);
    }

    /** Marks the document FAILED and acknowledges the message, without claiming it. */
    private void giveUp(String messageId) {
        List<MapRecord<String, String, String>> entries;
        try {
            entries = streamOps().range(properties.stream(), Range.just(messageId));
        } catch (DataAccessException e) {
            LOG.warn("Nao foi possivel ler a mensagem {} em {}; tentando na proxima rodada.",
                    messageId, properties.stream(), e);
            return;
        }
        Long documentId = entries.isEmpty() ? null : documentIdOf(entries.get(0));
        if (documentId == null) {
            queue.acknowledge(messageId);
            return;
        }

        LOG.warn("Mensagem {} do documento {} excedeu o limite de {} entregas.",
                messageId, documentId, properties.maxDeliveries());
        try {
            indexingService.recordFailure(documentId, new ProcessingFailureRequest(
                    GIVE_UP_CODE, GIVE_UP_MESSAGE, messageId));
        } catch (DocumentNotFoundException e) {
            LOG.warn("Documento {} nao existe mais; mensagem {} ja foi confirmada.", documentId, messageId);
        }
    }

    /**
     * The stream's own string entries, typed explicitly: {@code opsForStream()} alone infers
     * {@code Object} for a value never assigned straight to a variable.
     */
    private StreamOperations<String, String, String> streamOps() {
        return redis.opsForStream();
    }

    private Long documentIdOf(MapRecord<String, String, String> record) {
        String value = record.getValue().get(ProcessingQueue.DOCUMENT_ID_FIELD);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            LOG.error("Mensagem {} com documentId invalido: {}", record.getId(), value);
            return null;
        }
    }
}
