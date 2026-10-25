package com.akpedia.server.exception;

/**
 * Thrown when the worker's result cannot be stored as it is: a vector of the wrong size, or a
 * chunk without text. Nothing is written, and the stream message is left for the failure report
 * the worker sends next.
 */
public class InvalidEmbeddingsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidEmbeddingsException(Long documentId, String reason) {
        super("Embeddings invalidos para o documento %d: %s".formatted(documentId, reason));
    }

}
