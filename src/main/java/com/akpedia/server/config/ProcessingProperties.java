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
 * they read it with, and how the documents that did not make it to the stream are sent again.
 * The stream and group names are a contract with the worker (its {@code DOCUMENTS_STREAM} and
 * {@code WORKER_GROUP}): changing one side alone leaves messages nobody reads.
 *
 * @param stream         stream key an approved document's id is added to
 * @param group          consumer group the workers share, so each message goes to a single worker
 * @param retryInterval  how often to look for approved documents that were never enqueued
 * @param retryGrace     how long after its approval a document is left to the send that follows
 *                       its commit, before the retry takes it over
 * @param retryBatchSize how many documents one retry sends at most
 */
@Validated
@ConfigurationProperties(prefix = "akpedia.processing")
public record ProcessingProperties(
        @DefaultValue("documents:processing") @NotBlank String stream,
        @DefaultValue("ml-workers") @NotBlank String group,
        @DefaultValue("30s") @NotNull Duration retryInterval,
        @DefaultValue("1m") @NotNull Duration retryGrace,
        @DefaultValue("50") @Min(1) int retryBatchSize) {
}
