package com.akpedia.server.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * How the chat picks the passages it answers from, and how long it waits for the answer.
 *
 * <p>The answer is written by akpedia-ml, at the same base URL as the embeddings
 * ({@code akpedia.embedding.base-url}): it is the same service. Its timeouts are kept apart from
 * the embeddings' because a language model takes far longer than a vector -- akpedia-ml itself
 * waits up to 120 s for it -- and the embeddings' 30 s would cut every slow answer off.
 *
 * @param connectTimeout how long to wait for the TCP connection to akpedia-ml to open
 * @param readTimeout    how long to wait for the answer once connected; above akpedia-ml's own
 *                       {@code LLM_TIMEOUT_SECONDS}, so its 503 arrives instead of a cut connection
 * @param minimumScore   similarity below which a passage is not sent to the language model. With no
 *                       passage above it, the chat says it found nothing without asking the model
 * @param maxPassages    how many passages, at most, go to the language model, closest first
 */
@Validated
@ConfigurationProperties(prefix = "akpedia.chat")
public record ChatProperties(
        @DefaultValue("2s") @NotNull Duration connectTimeout,
        @DefaultValue("150s") @NotNull Duration readTimeout,
        @DefaultValue("0.80") @DecimalMin("-1.0") @DecimalMax("1.0") double minimumScore,
        @DefaultValue("6") @Min(1) @Max(20) int maxPassages) {
}
