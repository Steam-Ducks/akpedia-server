package com.akpedia.server.client;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.akpedia.server.config.GotenbergProperties;
import com.akpedia.server.exception.PdfConversionException;
import com.akpedia.server.exception.PdfConversionRejectedException;
import com.akpedia.server.exception.PdfConversionUnavailableException;

/**
 * The server's only door to Gotenberg, which turns arbitrary document formats into PDF
 * through a headless LibreOffice under the hood.
 *
 * <p>Every failure leaves here as a {@link PdfConversionException} carrying a message that
 * names the file and what was being done -- a caller should never have to read a raw
 * {@code ResourceAccessException} to find out that Gotenberg is not running.
 */
@Component
public class GotenbergClient {

    private static final String CONVERT_PATH = "/forms/libreoffice/convert";

    private final RestClient restClient;
    private final GotenbergProperties properties;

    public GotenbergClient(
            @Qualifier("gotenbergRestClient") RestClient gotenbergRestClient, GotenbergProperties properties) {
        this.restClient = gotenbergRestClient;
        this.properties = properties;
    }

    /**
     * Converts a file to PDF.
     *
     * @param content     raw bytes of the file
     * @param filename    original file name; Gotenberg picks the LibreOffice filter from its extension
     * @param contentType media type of the upload; {@code null} falls back to {@code application/octet-stream}
     * @return the converted PDF's bytes
     * @throws PdfConversionUnavailableException if Gotenberg is down or slower than the read timeout
     * @throws PdfConversionRejectedException    if Gotenberg was reached but refused the file
     */
    public byte[] convertToPdf(byte[] content, String filename, String contentType) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(mediaTypeOrDefault(contentType));

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new HttpEntity<>(new NamedByteArrayResource(content, filename), partHeaders));

        String operation = "the conversion of '%s' to PDF".formatted(filename);
        return call(operation, () -> restClient.post()
                .uri(CONVERT_PATH)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(byte[].class));
    }

    /**
     * Runs one call, translating every way it can go wrong into a {@link PdfConversionException}.
     *
     * @param operation what was being done, phrased to read inside the message
     */
    private byte[] call(String operation, Supplier<byte[]> request) {
        try {
            byte[] response = request.get();
            if (response == null || response.length == 0) {
                throw new PdfConversionException("Gotenberg answered %s with an empty body.".formatted(operation));
            }
            return response;
        } catch (RestClientResponseException e) {
            throw new PdfConversionRejectedException(refusedMessage(operation, e), e.getStatusCode(), e);
        } catch (RestClientException e) {
            if (isTransportFailure(e)) {
                throw new PdfConversionUnavailableException(unreachableMessage(operation), e);
            }
            throw new PdfConversionException(
                    "Gotenberg answered %s with a body this server could not read.".formatted(operation), e);
        }
    }

    /**
     * Tells "Gotenberg never answered" apart from "it answered something unexpected", the
     * same way {@code EmbeddingClient} does: by walking the cause chain for an
     * {@code IOException} instead of trusting the exception's own type.
     */
    private static boolean isTransportFailure(Throwable throwable) {
        for (Throwable cause = throwable; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private String unreachableMessage(String operation) {
        return ("Gotenberg is unreachable at %s%s, so %s did not happen "
                + "(connect timeout %s, read timeout %s). Check that the service is running and reachable.")
                .formatted(
                        properties.baseUrl(),
                        CONVERT_PATH,
                        operation,
                        format(properties.connectTimeout()),
                        format(properties.readTimeout()));
    }

    private String refusedMessage(String operation, RestClientResponseException e) {
        return "Gotenberg refused %s with HTTP %d: %s"
                .formatted(operation, e.getStatusCode().value(), firstLineOf(e.getResponseBodyAsString()));
    }

    private static MediaType mediaTypeOrDefault(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return MediaType.parseMediaType(contentType);
    }

    private static String firstLineOf(String body) {
        if (body == null || body.isBlank()) {
            return "no error body was sent.";
        }
        String line = body.strip().lines().findFirst().orElse("").strip();
        return line.length() > 200 ? line.substring(0, 200) + "..." : line;
    }

    private static String format(Duration duration) {
        return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + "s" : duration.toMillis() + "ms";
    }

    /**
     * Bytes that remember the file name they came from.
     *
     * <p>A bare {@code ByteArrayResource} is nameless, and the multipart part would go out
     * without a {@code filename}. Gotenberg picks the LibreOffice filter from the extension,
     * so a nameless upload would be rejected for a format it handles perfectly well.
     */
    private static final class NamedByteArrayResource extends ByteArrayResource {

        private final String filename;

        private NamedByteArrayResource(byte[] content, String filename) {
            super(content);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }

    }

}
