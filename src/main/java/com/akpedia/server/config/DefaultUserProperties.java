package com.akpedia.server.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The user that authors and approves everything while there is no registration or login.
 *
 * @param defaultUserEmail email of the user seeded by {@code V5__seed_default_approver.sql}.
 *                         Configured as an email rather than an id because the id is a sequence
 *                         value that differs between databases, while the email is what the
 *                         migration pins down
 */
@Validated
@ConfigurationProperties(prefix = "akpedia")
public record DefaultUserProperties(@NotBlank String defaultUserEmail) {
}
