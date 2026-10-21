package com.akpedia.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * The Redis stream that carries documents to the akpedia-ml workers, and the consumer group
 * they read it with. Both names are a contract with the worker (its {@code DOCUMENTS_STREAM}
 * and {@code WORKER_GROUP}): changing one side alone leaves messages nobody reads.
 *
 * @param stream stream key the server publishes uploaded documents to
 * @param group  consumer group the workers share, so each message goes to a single worker
 */
@Validated
@ConfigurationProperties(prefix = "akpedia.processing")
public record ProcessingProperties(
        @DefaultValue("documents:processing") @NotBlank String stream,
        @DefaultValue("ml-workers") @NotBlank String group) {
}
