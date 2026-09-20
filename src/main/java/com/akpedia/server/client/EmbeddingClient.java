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

import com.akpedia.server.config.EmbeddingProperties;
import com.akpedia.server.dto.DocumentEmbeddingResponse;
import com.akpedia.server.dto.EmbeddingErrorResponse;
import com.akpedia.server.dto.QueryEmbeddingResponse;
import com.akpedia.server.exception.EmbeddingException;
import com.akpedia.server.exception.EmbeddingRejectedException;
import com.akpedia.server.exception.EmbeddingUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * The server's only door to the embedding service (akpedia-ml).
 *
 * <p>That service is stateless and owns no database: it turns an uploaded document into
 * embedded chunks, and a search text into a comparable vector. Everything that is stored
 * or matched afterwards is this server's job, so these two calls are the entire contract.
 *
 * <p>Every failure leaves here as an {@link EmbeddingException} carrying a message that
 * names the service, the route and what was being done -- a caller should never have to
 * read a {@code ResourceAccessException} to find out that the service is not running.
 */
@Component
public class EmbeddingClient {

    private static final String DOCUMENT_PATH = "/api/v1/documents/process";
    private static final String QUERY_PATH = "/api/v1/embeddings/query";

    private final RestClient restClient;
    private final EmbeddingProperties properties;

    public EmbeddingClient(
            @Qualifier("embeddingRestClient") RestClient embeddingRestClient, EmbeddingProperties properties) {
        this.restClient = embeddingRestClient;
        this.properties = properties;
    }

    /**
     * Extracts, chunks and embeds a document.
     *
     * @param content     raw bytes of the file
     * @param filename    original file name; the extractor is picked from its extension
     * @param contentType media type of the upload, used when the name carries no extension;
     *                    {@code null} falls back to {@code application/octet-stream}
     * @return the chunks with one vector each
     * @throws EmbeddingUnavailableException if the service is down or slower than the read timeout
     * @throws EmbeddingRejectedException    if the document is refused (unsupported, too large, unreadable, no text)
     */
    public DocumentEmbeddingResponse embedDocument(byte[] content, String filename, String contentType) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(mediaTypeOrDefault(contentType));

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new NamedByteArrayResource(content, filename), partHeaders));

        return call("the parsing of document '" + filename + "'", DOCUMENT_PATH, () -> restClient.post()
                .uri(DOCUMENT_PATH)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(DocumentEmbeddingResponse.class));
    }

    /**
     * Embeds a search text into a vector comparable to the document chunks'.
     *
     * @param text what the user typed in the search box
     * @return the search vector and the model that produced it
     * @throws EmbeddingUnavailableException if the service is down or slower than the read timeout
     * @throws EmbeddingRejectedException    if the text is refused (empty or too long)
     */
    public QueryEmbeddingResponse embedQuery(String text) {
        return call("the embedding of the search text", QUERY_PATH, () -> restClient.post()
                .uri(QUERY_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new QueryRequest(text))
                .retrieve()
                .body(QueryEmbeddingResponse.class));
    }

    /**
     * Runs one call, translating every way it can go wrong into an {@link EmbeddingException}.
     *
     * @param operation what was being done, phrased to read inside the message
     * @param path      route being called, so the message points at a specific endpoint
     */
    private <T> T call(String operation, String path, Supplier<T> request) {
        try {
            T response = request.get();
            if (response == null) {
                throw new EmbeddingException(
                        "The embedding service answered %s with an empty body for %s.".formatted(path, operation));
            }
            return response;
        } catch (RestClientResponseException e) {
            EmbeddingErrorResponse error = errorBodyOf(e);
            throw new EmbeddingRejectedException(refusedMessage(operation, e, error), e.getStatusCode(), error, e);
        } catch (RestClientException e) {
            if (isTransportFailure(e)) {
                throw new EmbeddingUnavailableException(unreachableMessage(operation, path), e);
            }
            throw new EmbeddingException(
                    "The embedding service answered %s with a body this server could not read, during %s."
                            .formatted(path, operation),
                    e);
        }
    }

    /**
     * Tells "the service never answered" apart from "it answered something unexpected".
     *
     * <p>A read timeout does not always arrive as {@code ResourceAccessException}: with the
     * default request factory, one that lands while the response body is being consumed
     * surfaces as a plain {@code RestClientException} wrapping the {@code SocketTimeoutException}.
     * Matching on the exception type alone would file that outage under "unreadable body",
     * so the cause chain is what decides.
     *
     * <p>Jackson failures are excluded on the way down: {@code JsonProcessingException} is
     * an {@code IOException} too, but it means the bytes did arrive and simply were not the
     * answer this client expected -- retrying that would fail exactly the same way.
     */
    private static boolean isTransportFailure(Throwable throwable) {
        for (Throwable cause = throwable; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            if (cause instanceof JsonProcessingException) {
                return false;
            }
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private String unreachableMessage(String operation, String path) {
        return ("The embedding service is unreachable at %s%s, so %s did not happen "
                + "(connect timeout %s, read timeout %s). Check that the service is running and reachable.")
                .formatted(
                        properties.baseUrl(),
                        path,
                        operation,
                        format(properties.connectTimeout()),
                        format(properties.readTimeout()));
    }

    private String refusedMessage(String operation, RestClientResponseException e, EmbeddingErrorResponse error) {
        String detail = error != null
                ? "[%s] %s".formatted(error.code(), error.message())
                : firstLineOf(e.getResponseBodyAsString());
        return "The embedding service refused %s with HTTP %d: %s"
                .formatted(operation, e.getStatusCode().value(), detail);
    }

    /**
     * Reads the service's error body, tolerating the answers that do not carry one.
     *
     * <p>A 502 from a proxy or a 500 from an unhandled error reaches us as HTML or as an
     * empty body. That is not worth a second failure on top of the first, so it degrades
     * to {@code null} and the raw body is quoted in the message instead.
     */
    private EmbeddingErrorResponse errorBodyOf(RestClientResponseException e) {
        try {
            EmbeddingErrorResponse error = e.getResponseBodyAs(EmbeddingErrorResponse.class);
            return error != null && error.code() != null ? error : null;
        } catch (RestClientException | IllegalStateException ignored) {
            return null;
        }
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

    /** Body of the query embedding route. */
    private record QueryRequest(String text) {
    }

    /**
     * Bytes that remember the file name they came from.
     *
     * <p>A bare {@code ByteArrayResource} is nameless, and the multipart part would go out
     * without a {@code filename}. The service picks the extractor from the extension, so a
     * nameless upload comes back as a 415 for a format it handles perfectly well.
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
