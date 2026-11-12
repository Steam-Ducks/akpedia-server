package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.akpedia.server.client.EmbeddingClient;
import com.akpedia.server.config.ChatProperties;
import com.akpedia.server.dto.EmbeddingModelInfo;
import com.akpedia.server.dto.QueryEmbeddingResponse;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.exception.EmbeddingException;
import com.akpedia.server.repository.EmbeddingRepository;

@ExtendWith(MockitoExtension.class)
class ChatRetrievalServiceTest {

    private static final long USER_ID = 20L;
    private static final String MODEL = "intfloat/multilingual-e5-small";
    private static final Set<Long> ALLOWED = Set.of(1L, 3L);
    private static final double MINIMUM_SCORE = 0.8;
    private static final int MAX_PASSAGES = 4;

    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private EmbeddingRepository embeddings;
    @Mock
    private CategoryAccessService categoryAccess;

    private ChatRetrievalService service;

    @BeforeEach
    void setUp() {
        ChatProperties properties = new ChatProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(150), MINIMUM_SCORE, MAX_PASSAGES);
        service = new ChatRetrievalService(embeddingClient, embeddings, categoryAccess, properties);
    }

    private static QueryEmbeddingResponse vector(int dimensions) {
        return new QueryEmbeddingResponse(
                new EmbeddingModelInfo(MODEL, dimensions, true), Collections.nCopies(dimensions, 0.5f));
    }

    @Test
    @DisplayName("the question's vector is matched in the allowed categories, with the configured threshold and limit")
    void searchesTheAllowedCategoriesWithTheConfiguredThresholdAndLimit() {
        given(categoryAccess.allowedCategoryIds(USER_ID)).willReturn(ALLOWED);
        given(embeddingClient.embedQuery("qual o prazo?")).willReturn(vector(Embedding.VECTOR_DIMENSIONS));
        given(embeddings.findTopChunks(anyString(), eq(MODEL), eq(Embedding.VECTOR_DIMENSIONS),
                eq(MINIMUM_SCORE), eq(ALLOWED), eq(MAX_PASSAGES))).willReturn(List.of());

        service.retrieve("  qual o prazo?  ", USER_ID);

        then(embeddings).should().findTopChunks(anyString(), eq(MODEL), eq(Embedding.VECTOR_DIMENSIONS),
                eq(MINIMUM_SCORE), eq(ALLOWED), eq(MAX_PASSAGES));
    }

    @Test
    @DisplayName("each row becomes a passage, in the query's order, several of one document included")
    void rowsBecomePassagesInOrder() {
        given(categoryAccess.allowedCategoryIds(USER_ID)).willReturn(ALLOWED);
        given(embeddingClient.embedQuery(anyString())).willReturn(vector(Embedding.VECTOR_DIMENSIONS));
        given(embeddings.findTopChunks(anyString(), anyString(), anyInt(), anyDouble(), any(), anyInt()))
                .willReturn(List.of(
                        new Object[] {"O reembolso sai em 7 dias.", 42L, "Manual de Reembolso", "Financeiro", 0.93, 2},
                        new Object[] {"Notas fiscais em ate 30 dias.", 42L, "Manual de Reembolso", "Financeiro", 0.88, 5},
                        new Object[] {"Viagens precisam de aprovacao.", 7, "Politica de Viagens", "Juridico", 0.81f, 0}));

        List<RetrievedPassage> passages = service.retrieve("prazo de reembolso", USER_ID);

        assertThat(passages).containsExactly(
                new RetrievedPassage(42L, "Manual de Reembolso", "Financeiro", "O reembolso sai em 7 dias.", 0.93, 2),
                new RetrievedPassage(42L, "Manual de Reembolso", "Financeiro", "Notas fiscais em ate 30 dias.", 0.88, 5),
                new RetrievedPassage(7L, "Politica de Viagens", "Juridico", "Viagens precisam de aprovacao.",
                        (double) 0.81f, 0));
    }

    @Test
    @DisplayName("a user whose sector sees no category gets nothing, and the question never reaches akpedia-ml")
    void noCategoryMeansNoCallAtAll() {
        given(categoryAccess.allowedCategoryIds(USER_ID)).willReturn(Set.of());

        assertThat(service.retrieve("qual o prazo?", USER_ID)).isEmpty();

        then(embeddingClient).should(never()).embedQuery(anyString());
        then(embeddings).should(never()).findTopChunks(anyString(), anyString(), anyInt(), anyDouble(), any(), anyInt());
    }

    @Test
    @DisplayName("a vector of another dimension is refused instead of compared")
    void vectorOfAnotherDimensionIsRefused() {
        given(categoryAccess.allowedCategoryIds(USER_ID)).willReturn(ALLOWED);
        given(embeddingClient.embedQuery(anyString())).willReturn(vector(3));

        assertThatThrownBy(() -> service.retrieve("qual o prazo?", USER_ID))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("dimensions");
        then(embeddings).should(never()).findTopChunks(anyString(), anyString(), anyInt(), anyDouble(), any(), anyInt());
    }
}
