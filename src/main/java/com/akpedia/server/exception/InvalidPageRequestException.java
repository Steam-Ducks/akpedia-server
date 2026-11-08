package com.akpedia.server.exception;

/** A list was asked for with a negative page or a page size out of range. */
public class InvalidPageRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidPageRequestException(int maxSize) {
        super("page deve ser 0 ou mais, e size entre 1 e %d.".formatted(maxSize));
    }
}
