package com.akpedia.server.exception;

/**
 * The chat answer could not be written because akpedia-ml failed: it is down, slower than
 * {@code akpedia.chat.read-timeout}, its language model failed, or it answered something this
 * server cannot read.
 *
 * <p>One exception for all of them, on purpose: the person asking can only try again later, so
 * the API answers 503 in every case, and the message says which one it was for the logs.
 */
public class ChatUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChatUnavailableException(String message) {
        super(message);
    }

    public ChatUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

}
