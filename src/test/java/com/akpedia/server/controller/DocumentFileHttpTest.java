package com.akpedia.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.akpedia.server.dto.DocumentFileDescriptor;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.DocumentArchivedException;
import com.akpedia.server.service.DocumentFileService;
import com.akpedia.server.service.DocumentUploadService;

/**
 * Serves the route over a real HTTP connection, with a real PDF, and checks what a browser would
 * actually receive.
 *
 * <p>{@code DocumentControllerTest} exercises the same route through MockMvc, which never opens a
 * socket: headers are asserted as objects the test itself hands over. Here Tomcat writes the
 * response and a plain {@link HttpClient} reads it back, so the bytes on the wire, the encoded
 * {@code Content-Disposition}, the conditional request and the range request are all the real
 * thing. It is the closest this suite gets to pointing a browser at the URL.
 *
 * <p>Only the route is wired up -- no datasource, no Flyway, no JPA -- because what is under test
 * is the HTTP layer, and a document that must come from Postgres would make this unrunnable
 * wherever the database is not up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = DocumentFileHttpTest.OnlyTheDocumentRoute.class)
class DocumentFileHttpTest {

    /** The route and its error translation, with everything that needs a database left out. */
    @Configuration
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class})
    @Import({DocumentController.class, ApiExceptionHandler.class})
    static class OnlyTheDocumentRoute {
    }

    private static final Instant LAST_MODIFIED = Instant.parse("2026-09-24T22:31:05Z");
    private static final String ETAG = "\"1-1058-1790634665000\"";

    @LocalServerPort
    private int port;

    @MockBean
    private DocumentUploadService uploadService;

    @MockBean
    private DocumentFileService fileService;

    private final HttpClient http = HttpClient.newHttpClient();
    private byte[] pdf;

    @BeforeEach
    void setUp() throws IOException {
        pdf = Files.readAllBytes(Path.of("src/test/resources/manual-sample.pdf"));
        DocumentFileDescriptor file = new DocumentFileDescriptor(
                1L, "manual de integração.pdf", MediaType.APPLICATION_PDF, pdf.length, LAST_MODIFIED, ETAG);
        given(fileService.describe(1L)).willReturn(file);
        given(fileService.contentOf(file)).willReturn(pdf);
    }

    private HttpResponse<byte[]> get(String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:%d/api/v1/documents/1/file".formatted(port)));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    @Test
    @DisplayName("the PDF arrives over the wire byte for byte, with the headers a browser opens it by")
    void servesTheRealPdfOverHttp() throws Exception {
        HttpResponse<byte[]> response = get();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, HttpHeaders.CONTENT_TYPE)).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
        assertThat(header(response, HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf(pdf.length));
        assertThat(header(response, HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(header(response, HttpHeaders.ETAG)).isEqualTo(ETAG);
        assertThat(header(response, HttpHeaders.CACHE_CONTROL)).contains("private", "no-cache");

        // The name reaches the wire percent-encoded, which is how a browser recovers the accents.
        assertThat(header(response, HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("inline;")
                .contains("filename*=UTF-8''manual%20de%20integra%C3%A7%C3%A3o.pdf");

        assertThat(response.body()).isEqualTo(pdf);
        assertThat(new String(response.body(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("a HEAD answers the same headers with no body, which is how a viewer sizes the file up first")
    void answersHeadWithHeadersOnly() throws Exception {
        HttpResponse<byte[]> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:%d/api/v1/documents/1/file".formatted(port)))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf(pdf.length));
        assertThat(header(response, HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(header(response, HttpHeaders.ETAG)).isEqualTo(ETAG);
        assertThat(response.body()).isEmpty();
    }

    @Test
    @DisplayName("a browser that already holds the document is answered 304 with no body")
    void answersNotModifiedToACurrentCopy() throws Exception {
        HttpResponse<byte[]> response = get(HttpHeaders.IF_NONE_MATCH, ETAG);

        assertThat(response.statusCode()).isEqualTo(304);
        assertThat(response.body()).isEmpty();
        assertThat(header(response, HttpHeaders.ETAG)).isEqualTo(ETAG);
    }

    @Test
    @DisplayName("a PDF viewer asking for a slice gets 206 and exactly those bytes")
    void answersPartialContentToARangeRequest() throws Exception {
        HttpResponse<byte[]> response = get(HttpHeaders.RANGE, "bytes=0-7");

        assertThat(response.statusCode()).isEqualTo(206);
        assertThat(header(response, HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 0-7/" + pdf.length);
        assertThat(response.body()).isEqualTo(Arrays.copyOfRange(pdf, 0, 8));
    }

    @Test
    @DisplayName("a range past the end of the file is answered 416 with the real size")
    void answersRangeNotSatisfiable() throws Exception {
        HttpResponse<byte[]> response = get(HttpHeaders.RANGE, "bytes=99999-100000");

        assertThat(response.statusCode()).isEqualTo(416);
        assertThat(header(response, HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes */" + pdf.length);
    }

    @Test
    @DisplayName("the OpenAPI document still describes the route once, despite the hidden range handler")
    void openApiDescribesTheRouteOnce() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:%d/v3/api-docs".formatted(port))).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("/api/v1/documents/{id}/file");
        // Two handlers answer this path; only one may reach the document, or Swagger UI breaks on it.
        assertThat(response.body().split("\"/api/v1/documents/\\{id}/file\"")).hasSize(2);
        assertThat(response.body()).contains("\"206\"", "\"403\"", "\"409\"", "\"416\"");
    }

    @Test
    @DisplayName("a restricted document is refused over the wire as JSON, not as a broken PDF")
    void refusesAnArchivedDocumentAsJson() throws Exception {
        willThrow(new DocumentArchivedException(1L)).given(fileService).describe(1L);

        HttpResponse<byte[]> response = get();

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(header(response, HttpHeaders.CONTENT_TYPE)).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("document_archived");
    }

}
