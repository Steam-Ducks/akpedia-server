package com.akpedia.server.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.akpedia.server.config.ChatProperties;
import com.akpedia.server.config.EmbeddingProperties;
import com.akpedia.server.dto.ChatAnswer;
import com.akpedia.server.exception.ChatUnavailableException;
import com.akpedia.server.service.RetrievedPassage;

/**
 * Exercises {@link ChatClient} against a mocked akpedia-ml. The bodies follow its
 * {@code ChatAnswerRequest} and {@code ChatAnswerResponse} (src/app/api/chat.py), camelCase included.
 */
class ChatClientTest {

    private static final String BASE_URL = "http://ml.test:8000";
    private static final String ANSWER_URL = BASE_URL + "/api/v1/chat/answer";

    private MockRestServiceServer service;
    private ChatClient client;

    private static final List<RetrievedPassage> PASSAGES = List.of(
            new RetrievedPassage(42L, "Manual de Reembolso", "Financeiro", "O reembolso sai em 7 dias.", 0.93, 2),
            new RetrievedPassage(7L, "Politica de Viagens", null, "Viagens precisam de aprovacao.", 0.85, 0));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        service = MockRestServiceServer.bindTo(builder).build();
        client = new ChatClient(builder.build(),
                new EmbeddingProperties(BASE_URL, Duration.ofSeconds(2), Duration.ofSeconds(30)),
                new ChatProperties(Duration.ofSeconds(2), Duration.ofSeconds(150), 0.8, 6));
    }

    @Test
    @DisplayName("sends the question and the passages in order, in camelCase, with the document id as text")
    void sendsQuestionAndPassages() {
        service.expect(requestTo(ANSWER_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.question").value("Qual o prazo?"))
                .andExpect(jsonPath("$.passages.length()").value(2))
                .andExpect(jsonPath("$.passages[0].documentId").value("42"))
                .andExpect(jsonPath("$.passages[0].name").value("Manual de Reembolso"))
                .andExpect(jsonPath("$.passages[0].category").value("Financeiro"))
                .andExpect(jsonPath("$.passages[0].content").value("O reembolso sai em 7 dias."))
                .andExpect(jsonPath("$.passages[1].documentId").value("7"))
                .andRespond(withSuccess("""
                        {"answer": "O reembolso sai em 7 dias [1].", "found": true, "sources": ["42"]}
                        """, MediaType.APPLICATION_JSON));

        ChatAnswer answer = client.answer("Qual o prazo?", PASSAGES);

        assertThat(answer).isEqualTo(new ChatAnswer("O reembolso sai em 7 dias [1].", true, List.of("42")));
        service.verify();
    }

    @Test
    @DisplayName("missing sources are read as none")
    void missingSourcesAreEmpty() {
        service.expect(requestTo(ANSWER_URL)).andRespond(withSuccess("""
                {"answer": "Não encontrei essa informação nos documentos disponíveis.", "found": false}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.answer("Qual o prazo?", PASSAGES).sources()).isEmpty();
    }

    @Test
    @DisplayName("a 503 from the language model is a ChatUnavailableException naming the code")
    void languageModelFailureIsUnavailable() {
        service.expect(requestTo(ANSWER_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\": \"llm_unavailable\", \"message\": \"The language model did not answer in time.\"}"));

        assertThatThrownBy(() -> client.answer("Qual o prazo?", PASSAGES))
                .isInstanceOf(ChatUnavailableException.class)
                .hasMessageContaining("HTTP 503")
                .hasMessageContaining("llm_unavailable");
    }

    @Test
    @DisplayName("a 500 with no JSON body is a ChatUnavailableException too")
    void serverErrorWithoutBodyIsUnavailable() {
        service.expect(requestTo(ANSWER_URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_HTML).body("<html>oops</html>"));

        assertThatThrownBy(() -> client.answer("Qual o prazo?", PASSAGES))
                .isInstanceOf(ChatUnavailableException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    @DisplayName("a timeout is a ChatUnavailableException naming the timeouts")
    void timeoutIsUnavailable() {
        service.expect(requestTo(ANSWER_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.answer("Qual o prazo?", PASSAGES))
                .isInstanceOf(ChatUnavailableException.class)
                .hasMessageContaining("read timeout 150s")
                .hasMessageContaining("Read timed out");
    }

    @Test
    @DisplayName("an answer without found is refused rather than read as not found")
    void answerWithoutFoundIsUnavailable() {
        service.expect(requestTo(ANSWER_URL))
                .andRespond(withSuccess("{\"answer\": \"oi\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.answer("Qual o prazo?", PASSAGES))
                .isInstanceOf(ChatUnavailableException.class);
    }
}
