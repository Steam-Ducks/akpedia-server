package com.akpedia.server.exception;

/**
 * Thrown when a stored file cannot be read as a PDF to be rendered.
 *
 * <p>Every stored file went through the upload's conversion, so this means the bytes in the
 * database are damaged -- a failure of this server, not of the request.
 */
public class DocumentRenderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentRenderException(Long documentId, Throwable cause) {
        super("O arquivo do documento %d nao pode ser lido como PDF.".formatted(documentId), cause);
    }

}
