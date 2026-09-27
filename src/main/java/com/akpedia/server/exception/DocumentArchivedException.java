package com.akpedia.server.exception;

/** Thrown when the file of an archived document is requested. */
public class DocumentArchivedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentArchivedException(Long documentId) {
        super("O documento %d esta arquivado e nao pode ser aberto.".formatted(documentId));
    }

}
