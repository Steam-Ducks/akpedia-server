package com.akpedia.server.exception;

/**
 * Thrown when the document exists but its binary does not.
 *
 * <p>An upload saves metadata and binary in the same transaction, so this only happens if the
 * {@code document_files} row was removed by hand. It stays apart from
 * {@link DocumentNotFoundException} so the log says which of the two rows is missing.
 */
public class DocumentFileNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DocumentFileNotFoundException(Long documentId) {
        super("O documento %d existe, mas o arquivo dele nao esta armazenado.".formatted(documentId));
    }

}
