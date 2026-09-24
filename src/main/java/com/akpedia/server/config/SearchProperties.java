package com.akpedia.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Result limits and relevance threshold applied by semantic document search. */
@Validated
@ConfigurationProperties(prefix = "akpedia.search")
public record SearchProperties(
        @DefaultValue("10") @Min(1) int defaultLimit,
        @DefaultValue("50") @Min(1) @Max(100) int maxLimit,
        @DefaultValue("0.70") @DecimalMin("-1.0") @DecimalMax("1.0") double minimumScore) {
}
