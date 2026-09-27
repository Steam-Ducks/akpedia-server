package com.akpedia.server.exception;

/**
 * Base of every failure raised while talking to the embedding service.
 *
 * <p>Callers that only need to know "the embedding step did not happen" catch this one;
 * the subclasses separate the two cases worth telling apart: the service never answered
 * ({@link EmbeddingUnavailableException}) or it answered with a refusal
 * ({@link EmbeddingRejectedException}).
 */
public class EmbeddingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }

}
