package com.akpedia.server.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Where the embedding service is, and how long it is worth waiting for it.
 *
 * <p>The two timeouts are kept apart on purpose. Refusing a connection is immediate when
 * the service is down, while processing a document takes seconds: a single value would
 * serve both badly -- either the outage takes half a minute to notice, or uploads that
 * were still being answered get cut off.
 *
 * @param baseUrl        service base URL, with no trailing slash (e.g. {@code http://localhost:8000})
 * @param connectTimeout how long to wait for the TCP connection to open
 * @param readTimeout    how long to wait for the answer once connected
 */
@Validated
@ConfigurationProperties(prefix = "akpedia.embedding")
public record EmbeddingProperties(
        @DefaultValue("http://localhost:8000") @NotBlank String baseUrl,
        @DefaultValue("2s") @NotNull Duration connectTimeout,
        @DefaultValue("30s") @NotNull Duration readTimeout) {
}
