package com.akpedia.server.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The Redis stream that carries approved documents to the akpedia-ml workers, the consumer group
 * they read it with, and how messages that do not reach them -- never enqueued, or stuck pending
 * because the worker that read them died -- are sent again. The stream and group names are a
 * contract with the worker (its {@code DOCUMENTS_STREAM} and {@code WORKER_GROUP}): changing one
 * side alone leaves messages nobody reads.
 *
 * @param stream            stream key an approved document's id is added to
 * @param group             consumer group the workers share, so each message goes to a single worker
 * @param retryInterval     how often to look for approved documents that were never enqueued
 * @param retryGrace        how long after its approval a document is left to the send that follows
 *                          its commit, before the retry takes it over
 * @param retryBatchSize    how many documents one retry sends at most
 * @param recoveryInterval  how often to look for pending messages a dead worker never acknowledged
 * @param recoveryIdle      how long a message is left pending, with no delivery to its consumer,
 *                          before the recovery job takes it over
 * @param recoveryBatchSize how many pending messages one recovery run inspects at most
 * @param maxDeliveries     deliveries (including the recovery job's own redeliveries) past which
 *                          a message is given up on instead of handed out again; mirrors the
 *                          worker's own limit for the pending entries it resumes itself
 */
@Validated
@ConfigurationProperties(prefix = "akpedia.processing")
public record ProcessingProperties(
        @DefaultValue("documents:processing") @NotBlank String stream,
        @DefaultValue("ml-workers") @NotBlank String group,
        @DefaultValue("30s") @NotNull Duration retryInterval,
        @DefaultValue("1m") @NotNull Duration retryGrace,
        @DefaultValue("50") @Min(1) int retryBatchSize,
        @DefaultValue("1m") @NotNull Duration recoveryInterval,
        @DefaultValue("10m") @NotNull Duration recoveryIdle,
        @DefaultValue("50") @Min(1) int recoveryBatchSize,
        @DefaultValue("3") @Min(1) int maxDeliveries) {
}
