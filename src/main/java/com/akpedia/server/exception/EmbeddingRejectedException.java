package com.akpedia.server.exception;

import java.util.List;

import org.springframework.http.HttpStatusCode;

import com.akpedia.server.dto.EmbeddingErrorResponse;

/**
 * The embedding service was reached and refused the request.
 *
 * <p>The error body is kept whole, so callers can branch on the stable {@code code}
 * ({@code unsupported_format}, {@code file_too_large}, {@code empty_query}, ...) instead
 * of matching on the message text. It is absent when the service failed in a way that
 * produced no body of its own -- a 500 from an unhandled error, or a proxy answering in
 * its place.
 */
public class EmbeddingRejectedException extends EmbeddingException {

    private static final long serialVersionUID = 1L;

    private final transient HttpStatusCode statusCode;
    private final transient EmbeddingErrorResponse error;

    public EmbeddingRejectedException(
            String message, HttpStatusCode statusCode, EmbeddingErrorResponse error, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.error = error;
    }

    /** HTTP status answered by the service. */
    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    /** The parsed error body, or {@code null} when the service answered without one. */
    public EmbeddingErrorResponse getError() {
        return error;
    }

    /** Stable failure identifier, or {@code null} when there was no error body. */
    public String getCode() {
        return error != null ? error.code() : null;
    }

    /** Formats the service accepts, when the refusal was about the format; empty otherwise. */
    public List<String> getSupportedExtensions() {
        if (error == null || error.supportedExtensions() == null) {
            return List.of();
        }
        return List.copyOf(error.supportedExtensions());
    }

}
