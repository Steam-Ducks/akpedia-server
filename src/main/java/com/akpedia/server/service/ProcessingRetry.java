package com.akpedia.server.service;

import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import com.akpedia.server.config.ProcessingProperties;
import com.akpedia.server.repository.DocumentRepository;

/**
 * Sends again the approved documents that never reached the indexing worker: those whose send,
 * right after the approval committed, was cut short by the process dying or by Redis being down.
 *
 * <p>Only documents approved more than {@code retryGrace} ago are taken, so a document whose
 * first send is still in flight is not sent twice. A retry stops at the first document Redis
 * refuses: the rest would be refused too, and they will be tried again on the next run.
 */
@Component
public class ProcessingRetry implements SchedulingConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessingRetry.class);

    private final DocumentRepository documents;
    private final ProcessingQueue queue;
    private final ProcessingProperties properties;

    public ProcessingRetry(DocumentRepository documents, ProcessingQueue queue, ProcessingProperties properties) {
        this.documents = documents;
        this.queue = queue;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        // The first run waits one interval too: right at startup there is nothing it could not
        // also find a moment later, and the application is still warming up.
        registrar.addFixedDelayTask(
                new FixedDelayTask(this::retryDue, properties.retryInterval(), properties.retryInterval()));
    }

    /** Runs a retry over the documents whose grace period has passed. */
    void retryDue() {
        retry(OffsetDateTime.now().minus(properties.retryGrace()));
    }

    /**
     * Sends the documents approved before {@code approvedBefore} that were never enqueued.
     *
     * @param approvedBefore only documents approved before this are taken
     * @return how many were sent
     */
    int retry(OffsetDateTime approvedBefore) {
        List<Long> pending = documents.findPendingEnqueue(
                approvedBefore, PageRequest.of(0, properties.retryBatchSize()));

        int sent = 0;
        for (Long documentId : pending) {
            if (!queue.publish(documentId)) {
                break;
            }
            sent++;
        }
        if (!pending.isEmpty()) {
            LOG.info("Reenvio para indexacao: {} de {} documento(s) pendente(s) enviados.", sent, pending.size());
        }
        return sent;
    }
}
