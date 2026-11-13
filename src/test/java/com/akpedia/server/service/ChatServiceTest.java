package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.akpedia.server.client.ChatClient;
import com.akpedia.server.dto.ChatAnswer;
import com.akpedia.server.dto.ChatResponse;
import com.akpedia.server.exception.ChatUnavailableException;
import com.akpedia.server.exception.EmbeddingRejectedException;
import com.akpedia.server.exception.EmbeddingUnavailableException;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    private static final long USER_ID = 20L;

    @Mock
    private ChatRetrievalService retrieval;
    @Mock
    private ChatClient chatClient;

    private ChatService service;

    private static final RetrievedPassage REEMBOLSO_1 =
            new RetrievedPassage(42L, "Manual de Reembolso", "Financeiro", "O reembolso sai em 7 dias.", 0.93, 2);
    private static final RetrievedPassage REEMBOLSO_2 =
            new RetrievedPassage(42L, "Manual de Reembolso", "Financeiro", "Notas em ate 30 dias.", 0.88, 5);
    private static final RetrievedPassage VIAGENS =
            new RetrievedPassage(7L, "Politica de Viagens", "Juridico", "Viagens precisam de aprovacao.", 0.85, 0);

    @BeforeEach
    void setUp() {
        service = new ChatService(retrieval, chatClient);
    }

    @Test
    @DisplayName("with no passage above the threshold, not found -- and the language model is not asked")
    void noPassageMeansNotFoundWithoutTheModel() {
        given(retrieval.retrieve("qual o prazo?", USER_ID)).willReturn(List.of());

        ChatResponse response = service.answer("  qual o prazo?  ", USER_ID);

        assertThat(response).isEqualTo(new ChatResponse(ChatService.NOT_FOUND_ANSWER, false, List.of()));
        then(chatClient).should(never()).answer(anyString(), any());
    }

    @Test
    @DisplayName("the cited documents come back in the model's order, named from the passages sent, once each")
    void citedDocumentsAreNamedFromThePassages() {
        List<RetrievedPassage> passages = List.of(REEMBOLSO_1, VIAGENS, REEMBOLSO_2);
        given(retrieval.retrieve("qual o prazo?", USER_ID)).willReturn(passages);
        given(chatClient.answer("qual o prazo?", passages))
                .willReturn(new ChatAnswer("Viagens [2]; reembolso em 7 dias [1].", true, List.of("7", "42", "7")));

        ChatResponse response = service.answer("qual o prazo?", USER_ID);

        assertThat(response.found()).isTrue();
        assertThat(response.answer()).isEqualTo("Viagens [2]; reembolso em 7 dias [1].");
        assertThat(response.sources()).containsExactly(
                new ChatResponse.Source(7L, "Politica de Viagens", "Juridico"),
                new ChatResponse.Source(42L, "Manual de Reembolso", "Financeiro"));
    }

    @Test
    @DisplayName("a source that was not among the passages sent is dropped")
    void unknownSourceIsDropped() {
        given(retrieval.retrieve(anyString(), any())).willReturn(List.of(REEMBOLSO_1));
        given(chatClient.answer(anyString(), any()))
                .willReturn(new ChatAnswer("Algo [1].", true, List.of("999", "42")));

        assertThat(service.answer("qual o prazo?", USER_ID).sources())
                .containsExactly(new ChatResponse.Source(42L, "Manual de Reembolso", "Financeiro"));
    }

    @Test
    @DisplayName("when the passages do not answer, the model's sentence comes back with no sources")
    void modelNotFoundHasNoSources() {
        given(retrieval.retrieve(anyString(), any())).willReturn(List.of(REEMBOLSO_1));
        given(chatClient.answer(anyString(), any()))
                .willReturn(new ChatAnswer("Não encontrei.", false, List.of("42")));

        assertThat(service.answer("qual o prazo?", USER_ID))
                .isEqualTo(new ChatResponse("Não encontrei.", false, List.of()));
    }

    @Test
    @DisplayName("akpedia-ml down while embedding the question is a ChatUnavailableException")
    void embeddingOutageIsUnavailable() {
        given(retrieval.retrieve(anyString(), any()))
                .willThrow(new EmbeddingUnavailableException("down", new RuntimeException()));

        assertThatThrownBy(() -> service.answer("qual o prazo?", USER_ID))
                .isInstanceOf(ChatUnavailableException.class);
    }

    @Test
    @DisplayName("akpedia-ml refusing the question while embedding it (4xx) is a ChatUnavailableException too")
    void questionRefusalIsUnavailable() {
        given(retrieval.retrieve(anyString(), any())).willThrow(new EmbeddingRejectedException(
                "too long", HttpStatus.UNPROCESSABLE_ENTITY, null, new RuntimeException()));

        assertThatThrownBy(() -> service.answer("qual o prazo?", USER_ID))
                .isInstanceOf(ChatUnavailableException.class);
    }

    @Test
    @DisplayName("akpedia-ml failing with a 5xx while embedding the question is a ChatUnavailableException")
    void embeddingServerErrorIsUnavailable() {
        given(retrieval.retrieve(anyString(), any())).willThrow(new EmbeddingRejectedException(
                "boom", HttpStatus.INTERNAL_SERVER_ERROR, null, new RuntimeException()));

        assertThatThrownBy(() -> service.answer("qual o prazo?", USER_ID))
                .isInstanceOf(ChatUnavailableException.class);
    }
}
