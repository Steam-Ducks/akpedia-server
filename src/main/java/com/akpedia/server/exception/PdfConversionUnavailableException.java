package com.akpedia.server.exception;

/**
 * Gotenberg never answered: it is down, unreachable, or slower than the configured timeout.
 *
 * <p>Retrying the very same call later can work, which is what separates this from
 * {@link PdfConversionRejectedException} -- there, the service was reached and said no.
 */
public class PdfConversionUnavailableException extends PdfConversionException {

    private static final long serialVersionUID = 1L;

    public PdfConversionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

}
