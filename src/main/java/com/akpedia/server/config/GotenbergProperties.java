package com.akpedia.server.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Where Gotenberg is, and how long it is worth waiting for a conversion.
 *
 * <p>The read timeout is generous on purpose: a LibreOffice conversion of a large office
 * document can take much longer than a typical HTTP call, and cutting one off early would
 * turn a slow-but-working conversion into an avoidable failure.
 *
 * @param baseUrl        service base URL, with no trailing slash (e.g. {@code http://localhost:3000})
 * @param connectTimeout how long to wait for the TCP connection to open
 * @param readTimeout    how long to wait for the converted PDF to come back
 */
@Validated
@ConfigurationProperties(prefix = "akpedia.gotenberg")
public record GotenbergProperties(
        @DefaultValue("http://localhost:3000") @NotBlank String baseUrl,
        @DefaultValue("5s") @NotNull Duration connectTimeout,
        @DefaultValue("90s") @NotNull Duration readTimeout) {
}
