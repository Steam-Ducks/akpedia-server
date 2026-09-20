package com.akpedia.server.exception;

/**
 * The embedding service never answered: it is down, unreachable, or slower than the
 * configured timeout.
 *
 * <p>Retrying the very same call later can work, which is what separates this from
 * {@link EmbeddingRejectedException} -- there, the service was reached and said no.
 */
public class EmbeddingUnavailableException extends EmbeddingException {

    private static final long serialVersionUID = 1L;

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

}
