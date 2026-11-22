package com.akpedia.server.service;

/**
 * How one send went: delivered to the SMTP server, or why not.
 *
 * <p>"Sent" means the server accepted the message, which is as far as SMTP can tell: whether it
 * reached the inbox is the receiving side's business.
 */
public sealed interface MailResult {

    /** The recipient, as the caller gave it. */
    String to();

    /** Whether the SMTP server accepted the message. */
    default boolean sent() {
        return this instanceof Sent;
    }

    /**
     * The SMTP server accepted the message.
     *
     * @param messageId the message's {@code Message-ID}, as sent: what a bounce or a reply refers
     *                  back to, and what a record of who was notified can keep
     */
    record Sent(String to, String messageId) implements MailResult {
    }

    /**
     * The message did not go out.
     *
     * @param reason what kind of failure it was, which says whether trying again can help
     * @param detail what happened, for the logs or an admin; never shown to the recipient
     */
    record Failed(String to, Reason reason, String detail) implements MailResult {
    }

    /** Why a message did not go out. */
    enum Reason {
        /** The recipient, subject or body is missing, or the address is not an e-mail address. */
        INVALID_MESSAGE(false),
        /** The SMTP server refused the message for good (a 5xx reply), such as an address that does not exist. */
        REJECTED(false),
        /** The SMTP server refused the username or password: a configuration problem. */
        AUTHENTICATION(false),
        /**
         * The SMTP server could not be reached, did not answer within the timeouts, dropped the
         * connection, or refused the message for now (a 4xx reply, such as a full mailbox).
         */
        UNAVAILABLE(true),
        /** Anything else the server or the mail library reported. */
        FAILED(true);

        private final boolean retryable;

        Reason(boolean retryable) {
            this.retryable = retryable;
        }

        /** Whether the same send may work later without anything being fixed first. */
        public boolean retryable() {
            return retryable;
        }
    }
}
