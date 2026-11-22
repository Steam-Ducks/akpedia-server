package com.akpedia.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Who the server's e-mails come from, and how many go out per SMTP connection. Where they go out
 * through -- host, port, credentials and timeouts -- is Spring's own {@code spring.mail.*}.
 *
 * @param from      sender address; checked at startup, so a typo fails the boot instead of every send.
 *                  Its domain also names each message ({@code Message-ID})
 * @param fromName  name shown next to the address
 * @param batchSize how many messages {@code MailService.sendAll} sends over one connection before
 *                  opening the next: reusing it saves a connection and a login per message, and the
 *                  cap keeps one connection from carrying an unbounded number of them
 */
@Validated
@ConfigurationProperties(prefix = "akpedia.mail")
public record MailProperties(
        @DefaultValue("nao-responda@akpedia.local") @NotBlank @Email String from,
        @DefaultValue("Akpedia") @NotBlank String fromName,
        @DefaultValue("50") @Min(1) @Max(1000) int batchSize) {

    /** The part of {@link #from} after the {@code @}, which names the messages. */
    public String domain() {
        return from.substring(from.lastIndexOf('@') + 1);
    }
}
