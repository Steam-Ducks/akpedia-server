package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.akpedia.server.client.EmbeddingClient;
import com.akpedia.server.config.SearchProperties;
import com.akpedia.server.dto.EmbeddingModelInfo;
import com.akpedia.server.dto.QueryEmbeddingResponse;
import com.akpedia.server.dto.SearchResult;
import com.akpedia.server.exception.InvalidSearchRequestException;
import com.akpedia.server.repository.EmbeddingRepository;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    private static final int SNIPPET_LENGTH = 300;
    private static final OffsetDateTime UPDATED_AT = OffsetDateTime.of(2026, 9, 20, 14, 30, 0, 0, ZoneOffset.UTC);

    @Mock
    private EmbeddingClient embeddingClient;
    @Mock
    private EmbeddingRepository embeddings;

    private SearchService service;

    @BeforeEach
    void setUp() {
        service = new SearchService(embeddingClient, embeddings, properties(SNIPPET_LENGTH));
    }

    private static SearchProperties properties(int snippetLength) {
        return new SearchProperties(10, 50, 0.85, snippetLength);
    }

    /** One row of the similarity query, in the order it selects the columns. */
    private static Object[] row(String matchedChunk, int chunkIndex) {
        return new Object[] {
            1L, "manual.pdf", "manual tecnico", "application/pdf", matchedChunk, chunkIndex, 0.91,
            "Técnica", "Mariana Costa", Timestamp.from(UPDATED_AT.toInstant())
        };
    }

    /** Arranges the query embedding and the rows the repository answers with. */
    private void searchAnswers(List<Object[]> rows) {
        given(embeddingClient.embedQuery("technical manual"))
                .willReturn(new QueryEmbeddingResponse(model(), vector()));
        given(embeddings.search(anyString(), eq("fake-model"), eq(384), anyDouble(), anyInt()))
                .willReturn(rows);
    }

    /**
     * Overloaded rather than varargs, and with the element type spelled out: an {@code Object[]}
     * handed to a varargs method is read as the argument list, not as one argument.
     */
    private void searchAnswers(Object[] row) {
        searchAnswers(List.<Object[]>of(row));
    }

    private SearchResult firstResult() {
        return service.search(" technical manual ", null).get(0);
    }

    @Test
    @DisplayName("a result carries the document, its format, the matched snippet, where it was found, "
            + "its category, who is responsible and when it last changed")
    void mapsEveryFieldOfAResult() {
        searchAnswers(row("trecho encontrado", 3));

        assertThat(firstResult()).isEqualTo(new SearchResult(
                1L, "manual.pdf", "manual tecnico", "application/pdf", 0.91, "trecho encontrado", 3,
                "Técnica", "Mariana Costa", UPDATED_AT));
    }

    @Test
    @DisplayName("a chunk that already fits is returned whole, with no ellipsis")
    void shortChunkIsUntouched() {
        searchAnswers(row("um trecho curto", 0));

        assertThat(firstResult().matchedChunk()).isEqualTo("um trecho curto");
    }

    @Test
    @DisplayName("a long chunk is cut to the configured length, ellipsis included")
    void longChunkIsTrimmedToTheConfiguredLength() {
        searchAnswers(row("palavra ".repeat(100), 0));

        String snippet = firstResult().matchedChunk();

        assertThat(snippet).hasSizeLessThanOrEqualTo(SNIPPET_LENGTH).endsWith("…");
    }

    @Test
    @DisplayName("the cut lands on a whole word, never in the middle of one")
    void cutLandsOnAWordBoundary() {
        searchAnswers(row("integracao ".repeat(50), 0));

        String snippet = firstResult().matchedChunk();

        // Everything before the ellipsis is made of whole words, so nothing reads as a typo.
        assertThat(snippet.substring(0, snippet.length() - 1).split(" "))
                .allMatch(word -> word.equals("integracao"));
    }

    @Test
    @DisplayName("line breaks of the page the chunk came from collapse into single spaces")
    void whitespaceIsCollapsed() {
        searchAnswers(row("  primeira linha\n\n   segunda\tlinha  ", 0));

        assertThat(firstResult().matchedChunk()).isEqualTo("primeira linha segunda linha");
    }

    @Test
    @DisplayName("a single word longer than the budget is cut mid-word, not reduced to an ellipsis")
    void oneLongWordIsCutMidWord() {
        searchAnswers(row("a".repeat(400), 0));

        String snippet = firstResult().matchedChunk();

        assertThat(snippet).hasSize(SNIPPET_LENGTH).endsWith("…");
        assertThat(snippet.chars().filter(character -> character == 'a').count()).isEqualTo(SNIPPET_LENGTH - 1);
    }

    @Test
    @DisplayName("the snippet length is configuration, not a constant in the code")
    void snippetLengthComesFromConfiguration() {
        service = new SearchService(embeddingClient, embeddings, properties(80));
        searchAnswers(row("palavra ".repeat(100), 0));

        assertThat(firstResult().matchedChunk()).hasSizeLessThanOrEqualTo(80).endsWith("…");
    }

    @Test
    @DisplayName("results keep the order the query returned them in")
    void resultsKeepTheirOrder() {
        Object[] second = new Object[] {
            2L, "outro.pdf", null, "application/pdf", "outro trecho", 7, 0.88,
            "Normativa", "Rafael Mendes", Timestamp.from(UPDATED_AT.toInstant())
        };
        searchAnswers(List.<Object[]>of(row("trecho", 1), second));

        assertThat(service.search("technical manual", null))
                .extracting(SearchResult::documentId, SearchResult::chunkIndex)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 1), org.assertj.core.groups.Tuple.tuple(2L, 7));
    }

    @Test
    @DisplayName("a blank query is refused before the embedding service is called")
    void blankQueryIsRejectedBeforeCallingTheEmbeddingService() {
        assertThatThrownBy(() -> service.search("   ", 10))
                .isInstanceOf(InvalidSearchRequestException.class)
                .hasMessage("q must not be blank.");
    }

    @Test
    @DisplayName("a limit past the configured maximum is refused")
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
