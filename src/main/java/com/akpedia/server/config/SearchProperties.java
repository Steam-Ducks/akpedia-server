package com.akpedia.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Result limits, relevance threshold and snippet size applied by semantic document search.
 *
 * @param defaultLimit  how many results to return when the caller asks for no limit
 * @param maxLimit      the largest limit a caller may ask for
 * @param minimumScore  similarity below which a chunk is not worth returning
 * @param snippetLength how long the returned snippet may get, in characters, ellipsis included.
 *                      Bounded on both ends because the value only makes sense as a snippet: below
 *                      50 characters there is not enough of the text left to recognise it, and past
 *                      2000 the result stops being an excerpt and becomes the chunk itself
 */
@Validated
@ConfigurationProperties(prefix = "akpedia.search")
public record SearchProperties(
        @DefaultValue("10") @Min(1) int defaultLimit,
        @DefaultValue("50") @Min(1) @Max(100) int maxLimit,
        @DefaultValue("0.70") @DecimalMin("-1.0") @DecimalMax("1.0") double minimumScore,
        @DefaultValue("300") @Min(50) @Max(2000) int snippetLength) {
}
