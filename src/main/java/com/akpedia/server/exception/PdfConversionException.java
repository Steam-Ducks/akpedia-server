package com.akpedia.server.exception;

/**
 * Base of every failure raised while converting a document to PDF through Gotenberg.
 *
 * <p>Callers that only need to know "the conversion did not happen" catch this one; the
 * subclasses separate the two cases worth telling apart: Gotenberg never answered
 * ({@link PdfConversionUnavailableException}) or it answered with a refusal
 * ({@link PdfConversionRejectedException}).
 */
public class PdfConversionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PdfConversionException(String message) {
        super(message);
    }

    public PdfConversionException(String message, Throwable cause) {
        super(message, cause);
    }

}
