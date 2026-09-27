package com.akpedia.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.akpedia.server.config.EmbeddingClientConfig;
import com.akpedia.server.config.EmbeddingProperties;
import com.akpedia.server.exception.EmbeddingUnavailableException;
import com.sun.net.httpserver.HttpServer;

/**
 * Checks that the timeouts {@link EmbeddingClientConfig} configures are real.
 *
 * <p>{@link EmbeddingClientTest} mocks the transport, so it can only prove how the client
 * reacts to a timeout that something else declared. Here the HTTP call is genuine,
 * against a stand-in that accepts the connection and then goes quiet: without a read
 * timeout on the request factory the call would hang instead of failing, and this test
 * would never finish.
 */
class EmbeddingClientTimeoutTest {

    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    private static final Duration SILENCE = Duration.ofSeconds(5);

    private HttpServer serviceThatNeverAnswers;

    @BeforeEach
    void startSilentService() throws IOException {
        serviceThatNeverAnswers = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serviceThatNeverAnswers.createContext("/", exchange -> {
            try {
                Thread.sleep(SILENCE.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        serviceThatNeverAnswers.setExecutor(Executors.newSingleThreadExecutor());
        serviceThatNeverAnswers.start();
    }

    @AfterEach
    void stopSilentService() {
        serviceThatNeverAnswers.stop(0);
    }

    @Test
    @DisplayName("the service accepts the connection and stops answering: the call gives up at the read timeout")
    void readTimeoutIsEnforced() {
        String baseUrl = "http://127.0.0.1:" + serviceThatNeverAnswers.getAddress().getPort();
        EmbeddingClient client = clientFor(baseUrl, READ_TIMEOUT);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.embedQuery("qualquer texto"))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageContaining("The embedding service is unreachable at " + baseUrl)
                .hasMessageContaining("read timeout 300ms");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).as("gave up at the read timeout instead of waiting for the answer").isLessThan(SILENCE);
    }

    @Test
    @DisplayName("nothing is listening: the failure names the route and what did not happen")
    void connectionRefusedIsReported() throws IOException {
        String baseUrl = "http://127.0.0.1:" + closedPort();
        EmbeddingClient client = clientFor(baseUrl, READ_TIMEOUT);

        assertThatThrownBy(() -> client.embedDocument(new byte[] {1}, "manual.pdf", "application/pdf"))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageContaining(
                        "The embedding service is unreachable at " + baseUrl + "/api/v1/documents/process")
                .hasMessageContaining("the parsing of document 'manual.pdf' did not happen")
                .hasMessageContaining("Check that the service is running");
    }

    private static EmbeddingClient clientFor(String baseUrl, Duration readTimeout) {
        EmbeddingProperties properties = new EmbeddingProperties(baseUrl, Duration.ofMillis(500), readTimeout);
        RestClient restClient = new EmbeddingClientConfig().embeddingRestClient(RestClient.builder(), properties);
        return new EmbeddingClient(restClient, properties);
    }

    /** A port that was free a moment ago and has nothing bound to it now. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

}
