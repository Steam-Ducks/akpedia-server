package com.akpedia.server.exception;

/**
 * Thrown when the viewer asks for a page the document does not have.
 */
public class DocumentPageNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentPageNotFoundException(Long documentId, int page, int pageCount) {
        super("O documento %d nao tem a pagina %d (paginas: %d).".formatted(documentId, page, pageCount));
    }

}
