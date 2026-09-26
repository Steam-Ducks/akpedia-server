package com.akpedia.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import javax.imageio.ImageIO;

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
import com.akpedia.server.service.DocumentPreviewService;

/**
 * Renders the real sample PDF through the preview routes over HTTP, with only the file lookup
 * mocked, and checks the viewer gets pages -- never the PDF.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = DocumentPreviewHttpTest.OnlyThePreviewRoutes.class)
class DocumentPreviewHttpTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class})
    @Import({DocumentPreviewController.class, DocumentPreviewService.class, ApiExceptionHandler.class})
    static class OnlyThePreviewRoutes {
    }

    @LocalServerPort
    private int port;

    @MockBean
    private DocumentFileService fileService;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        byte[] pdf = Files.readAllBytes(Path.of("src/test/resources/manual-sample.pdf"));
        DocumentFileDescriptor file = new DocumentFileDescriptor(
                1L, "manual.pdf", MediaType.APPLICATION_PDF, pdf.length, Instant.EPOCH, "\"1\"");
        given(fileService.describe(1L)).willReturn(file);
        given(fileService.contentOf(file)).willReturn(pdf);
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:%d/api/v1/documents/1/preview%s".formatted(port, path)))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    @Test
    @DisplayName("the viewer learns the name and page count, uncached")
    void describesTheDocument() throws Exception {
        HttpResponse<byte[]> response = get("");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .contains("\"document_id\":1", "\"name\":\"manual.pdf\"")
                .containsPattern("\"page_count\":[1-9]");
    }

    @Test
    @DisplayName("a page arrives as a PNG image, uncached, and not as the PDF")
    void rendersAPageAsPng() throws Exception {
        HttpResponse<byte[]> response = get("/pages/1");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(header(response, HttpHeaders.CONTENT_TYPE)).isEqualTo(MediaType.IMAGE_PNG_VALUE);
        assertThat(header(response, HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(header(response, "X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(ImageIO.read(new ByteArrayInputStream(response.body()))).isNotNull()
                .satisfies(image -> assertThat(image.getWidth()).isGreaterThan(500));
    }

    @Test
    @DisplayName("a page past the end is refused as JSON 404")
    void refusesAPageOutOfRange() throws Exception {
        HttpResponse<byte[]> response = get("/pages/9999");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(header(response, HttpHeaders.CONTENT_TYPE)).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("document_page_not_found");
    }

    @Test
    @DisplayName("an archived document shows no pages")
    void refusesAnArchivedDocument() throws Exception {
        willThrow(new DocumentArchivedException(1L)).given(fileService).describe(1L);

        HttpResponse<byte[]> response = get("/pages/1");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("document_archived");
    }

}
