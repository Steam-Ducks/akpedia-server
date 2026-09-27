package com.akpedia.server.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Gotenberg was reached and refused the file -- typically because it is corrupted or in a
 * format none of its LibreOffice filters can open.
 */
public class PdfConversionRejectedException extends PdfConversionException {

    private static final long serialVersionUID = 1L;

    private final transient HttpStatusCode statusCode;

    public PdfConversionRejectedException(String message, HttpStatusCode statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /** HTTP status Gotenberg answered. */
    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

}
