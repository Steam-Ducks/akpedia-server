package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.akpedia.server.client.EmbeddingClient;
import com.akpedia.server.config.SearchProperties;
import com.akpedia.server.dto.EmbeddingModelInfo;
import com.akpedia.server.dto.QueryEmbeddingResponse;
import com.akpedia.server.exception.InvalidSearchRequestException;
import com.akpedia.server.repository.EmbeddingRepository;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private EmbeddingRepository embeddings;

    private SearchService service;

    @BeforeEach
    void setUp() {
        service = new SearchService(embeddingClient, embeddings, new SearchProperties(10, 50, 0.85));
    }

    @Test
    void returnsRankedUniqueDocumentsFromRepository() {
        given(embeddingClient.embedQuery("technical manual"))
                .willReturn(new QueryEmbeddingResponse(model(), vector()));
        given(embeddings.search(anyString(), eq("fake-model"), eq(384), eq(0.85), eq(10)))
            .willReturn(List.<Object[]>of(new Object[] {1L, "manual.pdf", "description", "chunk", 0.91}));

        assertThat(service.search(" technical manual ", null))
            .extracting(result -> result.documentId(), result -> result.score(), result -> result.matchedChunk())
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 0.91, "chunk"));
    }

    @Test
    void blankQueryIsRejectedBeforeCallingTheEmbeddingService() {
        assertThatThrownBy(() -> service.search("   ", 10))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessage("q must not be blank.");
    }

    @Test
    void limitMustStayWithinConfiguredBounds() {
        assertThatThrownBy(() -> service.search("manual", 51))
                .isInstanceOf(InvalidSearchRequestException.class);
    }

    private static EmbeddingModelInfo model() {
        return new EmbeddingModelInfo("fake-model", 384, true);
    }

    private static List<Float> vector() {
        return IntStream.range(0, 384).mapToObj(index -> (float) index).toList();
    }
}
