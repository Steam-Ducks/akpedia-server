package com.akpedia.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.akpedia.server.config.EmbeddingProperties;
import com.akpedia.server.dto.DocumentEmbeddingResponse;
import com.akpedia.server.dto.QueryEmbeddingResponse;
import com.akpedia.server.exception.EmbeddingException;
import com.akpedia.server.exception.EmbeddingRejectedException;
import com.akpedia.server.exception.EmbeddingUnavailableException;

/**
 * Exercises {@link EmbeddingClient} against a mocked embedding service.
 *
 * <p>The bodies below are copied from the shapes akpedia-ml actually answers, so a change
 * on its side that this client cannot read shows up here rather than in production.
 */
class EmbeddingClientTest {

    private static final String BASE_URL = "http://embedding.test:8000";
    private static final String DOCUMENT_URL = BASE_URL + "/api/v1/documents/process";
    private static final String QUERY_URL = BASE_URL + "/api/v1/embeddings/query";

    private static final EmbeddingProperties PROPERTIES =
            new EmbeddingProperties(BASE_URL, Duration.ofSeconds(2), Duration.ofSeconds(30));

    private MockRestServiceServer service;
    private EmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        this.service = MockRestServiceServer.bindTo(builder).build();
        this.client = new EmbeddingClient(builder.build(), PROPERTIES);
    }

    @Test
    @DisplayName("embedDocument sends the file as multipart and reads back the embedded chunks")
    void embedDocumentReturnsChunks() {
        service.expect(requestTo(DOCUMENT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", startsWith(MediaType.MULTIPART_FORM_DATA_VALUE)))
                .andExpect(content().string(containsString("name=\"file\"")))
                .andExpect(content().string(containsString("filename=\"manual.pdf\"")))
                .andRespond(withSuccess("""
                        {
                          "filename": "manual.pdf",
                          "model": {"name": "intfloat/multilingual-e5-small", "dimensions": 3, "normalized": true},
                          "chunk_count": 2,
                          "chunks": [
                            {"index": 0, "text": "Primeiro trecho.", "embedding": [0.1, 0.2, 0.3]},
                            {"index": 1, "text": "Segundo trecho.", "embedding": [0.4, 0.5, 0.6]}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        DocumentEmbeddingResponse response =
                client.embedDocument("conteudo".getBytes(StandardCharsets.UTF_8), "manual.pdf", "application/pdf");

        assertThat(response.filename()).isEqualTo("manual.pdf");
        assertThat(response.chunkCount()).isEqualTo(2);
        assertThat(response.model().name()).isEqualTo("intfloat/multilingual-e5-small");
        assertThat(response.model().dimensions()).isEqualTo(3);
        assertThat(response.model().normalized()).isTrue();
        assertThat(response.chunks()).hasSize(2);
        assertThat(response.chunks().get(1).index()).isEqualTo(1);
        assertThat(response.chunks().get(1).text()).isEqualTo("Segundo trecho.");
        assertThat(response.chunks().get(0).embedding()).containsExactly(0.1f, 0.2f, 0.3f);
        service.verify();
    }

    @Test
    @DisplayName("embedQuery posts the search text and reads back the vector")
    void embedQueryReturnsVector() {
        service.expect(requestTo(QUERY_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.text").value("qual e o prazo de garantia?"))
                .andRespond(withSuccess("""
                        {
                          "model": {"name": "intfloat/multilingual-e5-small", "dimensions": 3, "normalized": true},
                          "embedding": [0.012, -0.045, 0.7]
                        }
                        """, MediaType.APPLICATION_JSON));

        QueryEmbeddingResponse response = client.embedQuery("qual e o prazo de garantia?");

        assertThat(response.model().dimensions()).isEqualTo(3);
        assertThat(response.embedding()).containsExactly(0.012f, -0.045f, 0.7f);
        service.verify();
    }

    @Test
    @DisplayName("a refused document surfaces the service's code, message and supported formats")
    void unsupportedFormatBecomesRejected() {
        service.expect(requestTo(DOCUMENT_URL))
                .andRespond(withStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "code": "unsupported_format",
                                  "message": "No extractor handles '.xyz'.",
                                  "supported_extensions": [".pdf"]
                                }
                                """));

        assertThatThrownBy(() -> client.embedDocument(new byte[] {1}, "planilha.xyz", null))
                .isInstanceOf(EmbeddingRejectedException.class)
                .hasMessageContaining("The embedding service refused the parsing of document 'planilha.xyz'")
                .hasMessageContaining("HTTP 415")
                .hasMessageContaining("[unsupported_format]")
                .hasMessageContaining("No extractor handles '.xyz'.")
                .asInstanceOf(type(EmbeddingRejectedException.class))
                .satisfies(e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
                    assertThat(e.getCode()).isEqualTo("unsupported_format");
                    assertThat(e.getSupportedExtensions()).containsExactly(".pdf");
                });
        service.verify();
    }

    @Test
    @DisplayName("a refused query surfaces the service's code and carries no format list")
    void emptyQueryBecomesRejected() {
        service.expect(requestTo(QUERY_URL))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": \"empty_query\", \"message\": \"The search text is empty.\"}"));

        assertThatThrownBy(() -> client.embedQuery("   "))
                .isInstanceOf(EmbeddingRejectedException.class)
                .asInstanceOf(type(EmbeddingRejectedException.class))
                .satisfies(e -> {
                    assertThat(e.getCode()).isEqualTo("empty_query");
                    assertThat(e.getSupportedExtensions()).isEmpty();
                    assertThat(e.getError().message()).isEqualTo("The search text is empty.");
                });
        service.verify();
    }

    @Test
    @DisplayName("a failure with no error body still reports the status and the raw answer")
    void errorWithoutServiceBodyStillReportsStatus() {
        service.expect(requestTo(QUERY_URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html><body>502 Bad Gateway</body></html>"));

        assertThatThrownBy(() -> client.embedQuery("qualquer texto"))
                .isInstanceOf(EmbeddingRejectedException.class)
                .hasMessageContaining("HTTP 502")
                .hasMessageContaining("502 Bad Gateway")
                .asInstanceOf(type(EmbeddingRejectedException.class))
                .satisfies(e -> {
                    assertThat(e.getError()).isNull();
                    assertThat(e.getCode()).isNull();
                });
        service.verify();
    }

    @Test
    @DisplayName("service down: the connection is refused and the message says where and why")
    void connectionRefusedBecomesUnavailable() {
        service.expect(requestTo(QUERY_URL))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                            "I/O error on POST request", new ConnectException("Connection refused"));
                });

        assertThatThrownBy(() -> client.embedQuery("qualquer texto"))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageContaining("The embedding service is unreachable at " + QUERY_URL)
                .hasMessageContaining("the embedding of the search text did not happen")
                .hasMessageContaining("Check that the service is running")
                .hasRootCauseInstanceOf(ConnectException.class);
        service.verify();
    }

    @Test
    @DisplayName("service too slow: the read timeout is reported with the configured values")
    void readTimeoutBecomesUnavailable() {
        service.expect(requestTo(DOCUMENT_URL))
                .andRespond(request -> {
                    throw new ResourceAccessException(
                            "I/O error on POST request", new SocketTimeoutException("Read timed out"));
                });

        assertThatThrownBy(() -> client.embedDocument(new byte[] {1}, "manual.pdf", "application/pdf"))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageContaining("The embedding service is unreachable at " + DOCUMENT_URL)
                .hasMessageContaining("the parsing of document 'manual.pdf' did not happen")
                .hasMessageContaining("connect timeout 2s, read timeout 30s")
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
        service.verify();
    }

    @Test
    @DisplayName("a success body this client cannot read fails as EmbeddingException, not as a Jackson error")
    void unreadableSuccessBodyBecomesEmbeddingException() {
        service.expect(requestTo(QUERY_URL))
                .andRespond(withSuccess("nao e json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embedQuery("qualquer texto"))
                .isInstanceOf(EmbeddingException.class)
                .isNotInstanceOf(EmbeddingUnavailableException.class)
                .isNotInstanceOf(EmbeddingRejectedException.class)
                .hasMessageContaining("could not read");
        service.verify();
    }

}
