package com.akpedia.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.akpedia.server.config.ChatClientConfig;
import com.akpedia.server.config.ChatProperties;
import com.akpedia.server.config.EmbeddingProperties;
import com.akpedia.server.exception.ChatUnavailableException;
import com.akpedia.server.service.RetrievedPassage;
import com.sun.net.httpserver.HttpServer;

/**
 * Checks that the timeouts {@link ChatClientConfig} configures are real, and that they are the
 * chat's own: the embeddings' read timeout is set far shorter here, so a client built with it by
 * mistake would give up early and the elapsed-time check below would catch it.
 */
class ChatClientTimeoutTest {

    private static final Duration CHAT_READ_TIMEOUT = Duration.ofMillis(800);
    private static final Duration EMBEDDING_READ_TIMEOUT = Duration.ofMillis(50);
    private static final Duration SILENCE = Duration.ofSeconds(5);

    private static final List<RetrievedPassage> PASSAGES =
            List.of(new RetrievedPassage(42L, "Manual", "Financeiro", "trecho", 0.9, 0));

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
    @DisplayName("akpedia-ml accepts the connection and goes quiet: the call gives up at the chat's read timeout")
    void readTimeoutIsTheChatOne() {
        ChatClient client = clientFor("http://127.0.0.1:" + serviceThatNeverAnswers.getAddress().getPort());

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> client.answer("qual o prazo?", PASSAGES))
                .isInstanceOf(ChatUnavailableException.class)
                .hasMessageContaining("read timeout 800ms");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).as("waited for the chat's timeout, not the embeddings'").isGreaterThanOrEqualTo(CHAT_READ_TIMEOUT);
        assertThat(elapsed).as("gave up instead of waiting for the answer").isLessThan(SILENCE);
    }

    @Test
    @DisplayName("nothing is listening: a ChatUnavailableException naming the route")
    void connectionRefusedIsUnavailable() throws IOException {
        String baseUrl = "http://127.0.0.1:" + closedPort();

        assertThatThrownBy(() -> clientFor(baseUrl).answer("qual o prazo?", PASSAGES))
                .isInstanceOf(ChatUnavailableException.class)
                .hasMessageContaining(baseUrl + "/api/v1/chat/answer");
    }

    private static ChatClient clientFor(String baseUrl) {
        EmbeddingProperties embedding = new EmbeddingProperties(baseUrl, Duration.ofMillis(500), EMBEDDING_READ_TIMEOUT);
        ChatProperties chat = new ChatProperties(Duration.ofMillis(500), CHAT_READ_TIMEOUT, 0.8, 6);
        RestClient restClient = new ChatClientConfig().chatRestClient(RestClient.builder(), embedding, chat);
        return new ChatClient(restClient, embedding, chat);
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
