package com.akpedia.server.client;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.akpedia.server.config.ChatProperties;
import com.akpedia.server.config.EmbeddingProperties;
import com.akpedia.server.dto.ChatAnswer;
import com.akpedia.server.dto.EmbeddingErrorResponse;
import com.akpedia.server.exception.ChatUnavailableException;
import com.akpedia.server.service.RetrievedPassage;

/**
 * Asks akpedia-ml to write the chat's answer ({@code POST /api/v1/chat/answer}).
 *
 * <p>That service neither reads the database nor checks permissions: it answers only from the
 * passages sent here, already filtered by what the user may see, and says which documents it cited.
 * The passages go most relevant first, since it drops the last ones when they do not fit the model.
 *
 * <p>Built like {@link EmbeddingClient}, with its own {@code RestClient} and timeouts
 * ({@code akpedia.chat.*}). Every failure leaves as a {@link ChatUnavailableException}, whose
 * message names the route and what went wrong.
 */
@Component
public class ChatClient {

    static final String ANSWER_PATH = "/api/v1/chat/answer";

    private final RestClient restClient;
    private final EmbeddingProperties embedding;
    private final ChatProperties properties;

    public ChatClient(
            @Qualifier("chatRestClient") RestClient chatRestClient,
            EmbeddingProperties embedding,
            ChatProperties properties) {
        this.restClient = chatRestClient;
        this.embedding = embedding;
        this.properties = properties;
    }

    /**
     * Writes the answer to the question from the passages.
     *
     * @param question what the user asked
     * @param passages what to answer from, most relevant first
     * @return the answer, never {@code null}, with {@code found} and {@code sources} never {@code null}
     * @throws ChatUnavailableException if akpedia-ml is down, slower than the read timeout, refused
     *         the request, or answered with a body this server cannot read
     */
    public ChatAnswer answer(String question, List<RetrievedPassage> passages) {
        AnswerRequest body = new AnswerRequest(question, passages.stream().map(PassageOut::of).toList());
        ChatAnswer response;
        try {
            response = restClient.post()
                    .uri(ANSWER_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(ChatAnswer.class);
        } catch (RestClientResponseException e) {
            throw new ChatUnavailableException(refusedMessage(e), e);
        } catch (RestClientException e) {
            throw new ChatUnavailableException(failedMessage(e), e);
        }
        if (response == null || response.answer() == null || response.found() == null) {
            throw new ChatUnavailableException(
                    "akpedia-ml answered %s without an answer or without found.".formatted(ANSWER_PATH));
        }
        return new ChatAnswer(response.answer(), response.found(),
                response.sources() == null ? List.of() : List.copyOf(response.sources()));
    }

    /** A 503 from akpedia-ml's language model, or any other status it answered with. */
    private static String refusedMessage(RestClientResponseException e) {
        String detail;
        try {
            EmbeddingErrorResponse error = e.getResponseBodyAs(EmbeddingErrorResponse.class);
            detail = error != null && error.code() != null
                    ? "[%s] %s".formatted(error.code(), error.message())
                    : "no error body was sent.";
        } catch (RestClientException | IllegalStateException ignored) {
            detail = "the error body could not be read.";
        }
        return "akpedia-ml refused the chat answer at %s with HTTP %d: %s"
                .formatted(ANSWER_PATH, e.getStatusCode().value(), detail);
    }

    /** Never answered (down, past a timeout), or answered a body that is not the expected JSON. */
    private String failedMessage(RestClientException e) {
        return ("akpedia-ml did not answer the chat at %s%s as expected (connect timeout %s, read timeout %s): %s")
                .formatted(
                        embedding.baseUrl(),
                        ANSWER_PATH,
                        format(properties.connectTimeout()),
                        format(properties.readTimeout()),
                        e.getMostSpecificCause().getMessage());
    }

    private static String format(Duration duration) {
        return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + "s" : duration.toMillis() + "ms";
    }

    /** Body of the answer route, in the camelCase akpedia-ml reads. */
    private record AnswerRequest(String question, List<PassageOut> passages) {
    }

    /** One passage as akpedia-ml reads it: the document id goes as text, like it comes back in {@code sources}. */
    private record PassageOut(String documentId, String name, String category, String content) {

        static PassageOut of(RetrievedPassage passage) {
            return new PassageOut(
                    String.valueOf(passage.documentId()), passage.name(), passage.category(), passage.content());
        }
    }
}
