package com.akpedia.server.service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.akpedia.server.config.MailProperties;
import com.akpedia.server.service.MailResult.Failed;
import com.akpedia.server.service.MailResult.Reason;
import com.akpedia.server.service.MailResult.Sent;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Sends e-mail and reports how each message went instead of throwing.
 *
 * <p>Built for sending the same notice to many people, as the data breach notice does: one bad
 * address, or the server refusing one recipient, must not keep the rest from going out. So
 * {@link #send} and {@link #sendAll} never throw -- every failure comes back as a {@link Failed}
 * with its {@link Reason}, in the position of the message it belongs to.
 *
 * <p>{@link #sendAll} sends up to {@code akpedia.mail.batch-size} messages over one SMTP
 * connection, instead of opening a connection -- and logging in -- once per message. A connection
 * the server drops midway is opened again for the messages left, and a message the server refuses
 * fails alone ({@code MailServiceConnectionTest} pins both against a misbehaving server).
 *
 * <p>Each message is named after the sender's domain ({@code <uuid@akpedia.local>}), not after the
 * machine it left from, and that {@code Message-ID} comes back in {@link Sent}.
 *
 * <p>The logs carry no full e-mail address: who was notified of a breach is personal data in itself,
 * so recipients appear masked ({@code a***@empresa.com}).
 */
@Service
public class MailService {

    private static final Logger LOG = LoggerFactory.getLogger(MailService.class);

    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+)");

    private static final String MESSAGE_ID = "Message-ID";

    private final JavaMailSender mailSender;
    private final MailProperties properties;

    public MailService(JavaMailSender mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * Sends one message.
     *
     * @return {@link Sent} once the SMTP server accepted it, {@link Failed} otherwise; never throws
     */
    public MailResult send(OutgoingMail mail) {
        return sendAll(Collections.singletonList(mail)).get(0);
    }

    /**
     * Sends every message, a batch per connection; a failed message is recorded and the rest go on.
     *
     * @return one result per message, in the same order
     */
    public List<MailResult> sendAll(Collection<OutgoingMail> mails) {
        List<OutgoingMail> list = new ArrayList<>(mails);
        MailResult[] results = new MailResult[list.size()];

        List<Prepared> ready = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            OutgoingMail mail = list.get(i);
            String to = mail == null ? null : mail.to();
            String problem = problemWith(mail);
            if (problem != null) {
                results[i] = failed(to, Reason.INVALID_MESSAGE, problem, null);
                continue;
            }
            try {
                ready.add(prepare(i, mail));
            } catch (MessagingException | RuntimeException e) {
                results[i] = failed(to, Reason.INVALID_MESSAGE, describe(e), e);
            }
        }

        for (int first = 0; first < ready.size(); first += properties.batchSize()) {
            deliver(ready.subList(first, Math.min(first + properties.batchSize(), ready.size())), results);
        }

        List<MailResult> answered = Arrays.asList(results);
        long failures = answered.stream().filter(result -> !result.sent()).count();
        if (failures > 0) {
            LOG.warn("{} de {} e-mails nao foram enviados.", failures, answered.size());
        }
        return answered;
    }

    /**
     * Sends one batch over one connection, and fills in the result of each of its messages.
     *
     * <p>Spring's sender ({@code JavaMailSenderImpl.doSend}) reports the messages that failed in
     * {@link MailSendException#getFailedMessages}, keyed by the message itself; the ones not there
     * went out. Before each message it checks the connection is still open and opens a new one if not,
     * so a connection the server dropped costs at most the message it was carrying. A refused login,
     * or something the library was not expected to throw, is every message's failure.
     */
    private void deliver(List<Prepared> batch, MailResult[] results) {
        MimeMessage[] messages = batch.stream().map(Prepared::message).toArray(MimeMessage[]::new);
        Map<Object, Exception> failedMessages = Map.of();
        Reason wholeReason = null;
        Exception wholeFailure = null;
        try {
            mailSender.send(messages);
        } catch (MailAuthenticationException e) {
            wholeReason = Reason.AUTHENTICATION;
            wholeFailure = e;
        } catch (MailSendException e) {
            // Spring lists every message that did not go out -- a refused one, or all the ones left
            // when the connection could not be opened -- so the ones not listed went out. The list
            // is empty only when every message went out and closing the connection afterwards
            // failed: that must not turn delivered messages into failures.
            failedMessages = e.getFailedMessages();
            if (failedMessages.isEmpty()) {
                LOG.warn("Os e-mails do lote foram enviados, mas fechar a conexao SMTP falhou: {}", describe(e));
            }
        } catch (RuntimeException e) {
            // Whatever the library throws, the other batches still have to go out.
            wholeReason = reasonOf(e);
            wholeFailure = e;
        }

        for (Prepared prepared : batch) {
            String to = prepared.mail().to();
            Exception failure = wholeFailure != null ? wholeFailure : failedMessages.get(prepared.message());
            if (failure == null) {
                LOG.debug("E-mail para {} enviado ({}).", mask(to), prepared.messageId());
                results[prepared.index()] = new Sent(to, prepared.messageId());
            } else if (wholeReason == Reason.AUTHENTICATION) {
                results[prepared.index()] = failed(
                        to, Reason.AUTHENTICATION, "The SMTP server refused the username or password.", failure);
            } else {
                Reason reason = wholeReason != null ? wholeReason : reasonOf(failure);
                results[prepared.index()] = failed(to, reason, describe(failure), failure);
            }
        }
    }

    /** Why the message cannot be sent as it is, or {@code null} when it can. */
    private static String problemWith(OutgoingMail mail) {
        if (mail == null) {
            return "No message was given.";
        }
        if (mail.to() == null || mail.to().isBlank()) {
            return "The message has no recipient.";
        }
        try {
            InternetAddress address = new InternetAddress(mail.to().strip(), true);
            address.validate();
            if (address.getAddress().indexOf('@') < 1) {
                return "The recipient is not an e-mail address.";
            }
        } catch (AddressException e) {
            return "The recipient is not an e-mail address: " + e.getMessage();
        }
        if (mail.subject() == null || mail.subject().isBlank()) {
            return "The message has no subject.";
        }
        // A line break in a header would let the text inject headers of its own (Bcc, for one).
        if (mail.subject().indexOf('\r') >= 0 || mail.subject().indexOf('\n') >= 0) {
            return "The subject must be a single line.";
        }
        if (mail.body() == null) {
            return "The message has no body.";
        }
        return null;
    }

    /** The message, named after the sender's domain, ready to go out. */
    private Prepared prepare(int index, OutgoingMail mail) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        try {
            helper.setFrom(properties.from(), properties.fromName());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is always supported; this cannot happen, but the signature says it may.
            throw new IllegalStateException(e);
        }
        helper.setTo(new InternetAddress(mail.to().strip(), true));
        helper.setSubject(mail.subject());
        helper.setText(mail.body(), false);
        // Set before sending, so Jakarta Mail does not name it after the machine; Spring's sender
        // keeps a Message-ID already there.
        String messageId = "<" + UUID.randomUUID() + "@" + properties.domain() + ">";
        message.setHeader(MESSAGE_ID, messageId);
        return new Prepared(index, mail, message, messageId);
    }

    /** A valid message, built, and where its result goes. */
    private record Prepared(int index, OutgoingMail mail, MimeMessage message, String messageId) {
    }

    /**
     * Tells a refused recipient from a server that could not take the message now, by the SMTP reply.
     *
     * <p>The class of the exception does not tell them apart: Angus Mail throws the same
     * {@code SMTPSendFailedException} for a 550 "no such user" and for a connection that dropped
     * mid-message (reply code -1, "[EOF]"). The reply code does, as SMTP defines it: 5xx is a
     * permanent refusal, 4xx a temporary one (a full mailbox, greylisting), and anything else means
     * no answer came back. The whole exception tree is walked, since Spring wraps what Jakarta Mail
     * threw and Jakarta Mail chains its own exceptions through {@code getNextException}.
     */
    static Reason reasonOf(Exception failure) {
        boolean permanent = false;
        boolean temporary = false;
        boolean refusedAddress = false;
        for (Throwable cause : causesOf(failure)) {
            int code = replyCodeOf(cause);
            if (code >= 500 && code < 600) {
                permanent = true;
            } else if (code != NO_REPLY_CODE || cause instanceof IOException) {
                // A 4xx, or no reply at all (-1): the server may take it later.
                temporary = true;
            }
            if (cause instanceof SendFailedException sendFailed && code == NO_REPLY_CODE
                    && sendFailed.getInvalidAddresses() != null && sendFailed.getInvalidAddresses().length > 0) {
                refusedAddress = true;
            }
        }
        if (permanent || refusedAddress) {
            return Reason.REJECTED;
        }
        return temporary ? Reason.UNAVAILABLE : Reason.FAILED;
    }

    /** Stands for "not an SMTP reply" in {@link #replyCodeOf}. */
    private static final int NO_REPLY_CODE = Integer.MIN_VALUE;

    /**
     * The SMTP reply code an Angus Mail exception carries: the server's answer, or -1 when the
     * connection ended before one came; {@link #NO_REPLY_CODE} for any other exception.
     */
    private static int replyCodeOf(Throwable throwable) {
        if (throwable instanceof SMTPAddressFailedException e) {
            return e.getReturnCode();
        }
        if (throwable instanceof SMTPSendFailedException e) {
            return e.getReturnCode();
        }
        if (throwable instanceof SMTPSenderFailedException e) {
            return e.getReturnCode();
        }
        return NO_REPLY_CODE;
    }

    private static List<Throwable> causesOf(Throwable failure) {
        List<Throwable> found = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collect(failure, found, seen);
        return found;
    }

    private static void collect(Throwable throwable, List<Throwable> found, Set<Throwable> seen) {
        if (throwable == null || !seen.add(throwable)) {
            return;
        }
        found.add(throwable);
        collect(throwable.getCause(), found, seen);
        if (throwable instanceof MessagingException messaging) {
            collect(messaging.getNextException(), found, seen);
        }
        if (throwable instanceof MailSendException send) {
            for (Exception each : send.getMessageExceptions()) {
                collect(each, found, seen);
            }
            for (Map.Entry<Object, Exception> each : send.getFailedMessages().entrySet()) {
                collect(each.getValue(), found, seen);
            }
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message.strip();
    }

    private static Failed failed(String to, Reason reason, String detail, Throwable cause) {
        // The stack trace only at debug: a batch to hundreds of people with the server down would
        // otherwise bury the log in hundreds of identical traces.
        LOG.warn("E-mail para {} nao enviado ({}): {}", mask(to), reason, maskEmails(detail));
        if (cause != null) {
            LOG.debug("Causa da falha no envio para {}", mask(to), cause);
        }
        return new Failed(to, reason, detail);
    }

    /**
     * The recipient with all but the first letter of the mailbox hidden, for the logs.
     *
     * <p>Everything before the last {@code @} is the mailbox, valid or not: a pattern for valid
     * addresses would leave the rest of an invalid one in the clear ({@code ana silva@empresa.com}
     * would log {@code ana s***@empresa.com}). Without an {@code @}, only the first letter shows.
     */
    static String mask(String address) {
        if (address == null || address.isBlank()) {
            return "(sem destinatario)";
        }
        String stripped = address.strip();
        int at = stripped.lastIndexOf('@');
        String mailbox = at < 0 ? stripped : stripped.substring(0, at);
        String domain = at < 0 ? "" : stripped.substring(at);
        return (mailbox.isEmpty() ? "" : mailbox.substring(0, 1)) + "***" + domain;
    }

    /** Every e-mail address in the text, masked: servers quote the refused address in their answer. */
    static String maskEmails(String text) {
        return text == null ? null : EMAIL.matcher(text).replaceAll("$1***@$2");
    }
}
