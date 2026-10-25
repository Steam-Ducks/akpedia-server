package com.akpedia.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The shared secret the akpedia-ml worker sends on the {@code /internal} routes.
 *
 * <p>Empty by default, and an empty token opens nothing: every {@code /internal} call answers 401
 * until {@code INTERNAL_API_TOKEN} is set, here and in the worker, to the same value.
 *
 * @param token value expected in the {@code X-Internal-Token} header
 */
@ConfigurationProperties(prefix = "akpedia.internal")
public record InternalApiProperties(@DefaultValue("") String token) {

    /** Whether a token was configured at all. */
    public boolean hasToken() {
        return token != null && !token.isBlank();
    }
}
