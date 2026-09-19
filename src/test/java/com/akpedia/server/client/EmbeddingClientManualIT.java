package com.akpedia.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.akpedia.server.config.EmbeddingClientConfig;
import com.akpedia.server.config.EmbeddingProperties;
import com.akpedia.server.dto.DocumentEmbeddingResponse;
import com.akpedia.server.dto.QueryEmbeddingResponse;
import com.akpedia.server.exception.EmbeddingRejectedException;
import com.akpedia.server.exception.EmbeddingUnavailableException;

/**
 * Manual check against a real embedding service, for when mocks are not enough.
 *
 * <p>The name ends in {@code IT}, so {@code mvn test} never picks it up: it needs a
 * service listening somewhere, which CI has no reason to provide. Run it by hand:
 *
 * <pre>
 * EMBEDDING_BASE_URL=http://127.0.0.1:8000 ./mvnw test -Dtest=EmbeddingClientManualIT
 * </pre>
 *
 * <p>It prints what came back, so the answers can be eyeballed -- the point is to see the
 * real shapes, not only to see green.
 */
class EmbeddingClientManualIT {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8000";

    private final EmbeddingProperties properties = new EmbeddingProperties(
            System.getenv().getOrDefault("EMBEDDING_BASE_URL", DEFAULT_BASE_URL),
            Duration.ofSeconds(5),
            Duration.ofSeconds(120));

    private final EmbeddingClient client = new EmbeddingClient(
            new EmbeddingClientConfig().embeddingRestClient(RestClient.builder(), properties), properties);

    @Test
    @DisplayName("a real PDF comes back as chunks with vectors")
    void embedDocument() throws IOException {
        DocumentEmbeddingResponse response = client.embedDocument(samplePdf(), "manual.pdf", "application/pdf");

        print("embedDocument", "filename=%s model=%s dims=%d normalized=%s chunks=%d".formatted(
                response.filename(),
                response.model().name(),
                response.model().dimensions(),
                response.model().normalized(),
                response.chunkCount()));
        response.chunks().forEach(chunk -> print("  chunk " + chunk.index(),
                "%d dims | %s".formatted(chunk.embedding().size(), chunk.text())));

        assertThat(response.chunks()).isNotEmpty().hasSize(response.chunkCount());
        assertThat(response.chunks()).allSatisfy(chunk ->
                assertThat(chunk.embedding()).hasSize(response.model().dimensions()));
    }

    @Test
    @DisplayName("a search text comes back as a vector of the same width")
    void embedQuery() {
        QueryEmbeddingResponse response = client.embedQuery("qual e o prazo de garantia do equipamento?");

        print("embedQuery", "model=%s dims=%d vector=%d".formatted(
                response.model().name(), response.model().dimensions(), response.embedding().size()));

        assertThat(response.embedding()).hasSize(response.model().dimensions());
    }

    @Test
    @DisplayName("an unsupported file is refused with a code and the accepted formats")
    void refusedDocument() {
        assertThatThrownBy(() -> client.embedDocument(new byte[] {1, 2, 3}, "planilha.xyz", null))
                .isInstanceOf(EmbeddingRejectedException.class)
                .satisfies(thrown -> {
                    EmbeddingRejectedException e = (EmbeddingRejectedException) thrown;
                    print("refusedDocument", "status=%s code=%s supported=%s"
                            .formatted(e.getStatusCode(), e.getCode(), e.getSupportedExtensions()));
                    print("  message", e.getMessage());
                    assertThat(e.getCode()).isEqualTo("unsupported_format");
                });
    }

    @Test
    @DisplayName("an empty search text is refused with a code")
    void refusedQuery() {
        assertThatThrownBy(() -> client.embedQuery("   "))
                .isInstanceOf(EmbeddingRejectedException.class)
                .satisfies(thrown -> {
                    EmbeddingRejectedException e = (EmbeddingRejectedException) thrown;
                    print("refusedQuery", "code=" + e.getCode());
                    print("  message", e.getMessage());
                    assertThat(e.getCode()).isEqualTo("empty_query");
                });
    }

    @Test
    @DisplayName("a port with nothing behind it produces the service-is-down message")
    void serviceDown() {
        EmbeddingProperties down =
                new EmbeddingProperties("http://127.0.0.1:59999", Duration.ofMillis(500), Duration.ofSeconds(2));
        EmbeddingClient dead = new EmbeddingClient(
                new EmbeddingClientConfig().embeddingRestClient(RestClient.builder(), down), down);

        assertThatThrownBy(() -> dead.embedQuery("qualquer texto"))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .satisfies(e -> print("serviceDown", e.getMessage()));
    }

    private static byte[] samplePdf() throws IOException {
        try (InputStream pdf = EmbeddingClientManualIT.class.getResourceAsStream("/manual-sample.pdf")) {
            assertThat(pdf).as("src/test/resources/manual-sample.pdf").isNotNull();
            return pdf.readAllBytes();
        }
    }

    private static void print(String label, String detail) {
        System.out.printf("[manual-it] %-18s %s%n", label, detail);
    }

}
