package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.akpedia.server.config.SearchProperties;
import com.akpedia.server.dto.DocumentSummary;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.PendingDocument;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.InvalidDocumentListRequestException;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.EmbeddingRepository;

@ExtendWith(MockitoExtension.class)
class DocumentListServiceTest {

    private static final int SNIPPET_LENGTH = 80;

    @Mock
    private DocumentRepository documents;
    @Mock
    private EmbeddingRepository embeddings;
    @Mock
    private CategoryAccessService categoryAccess;

    private DocumentListService service;

    private static final long USER_ID = 20L;
    private static final Set<Long> ALLOWED = Set.of(1L, 3L);

    @BeforeEach
    void setUp() {
        service = new DocumentListService(
                documents, embeddings, new SearchProperties(10, 50, 0.85, SNIPPET_LENGTH), categoryAccess);
        // Lenient: the review queue and the refused limits never read the sector.
        lenient().when(categoryAccess.allowedCategoryIds(USER_ID)).thenReturn(ALLOWED);
    }

    private static Document document(long id) {
        User creator = new User("Mariana Costa", "mariana@akpedia.test", new Sector("Engenharia", null));
        Document document = new Document(
                "manual.pdf", "application/pdf", 1024L, new Category("Manuais", null), creator, DocumentStatus.DRAFT);
        ReflectionTestUtils.setField(document, "id", id);
        return document;
    }

    @Test
    @DisplayName("each document carries the start of its text, trimmed like a search snippet")
    void excerptIsTheTrimmedFirstChunk() {
        given(documents.findRecent(any(), any(), any())).willReturn(List.of(document(1L), document(2L)));
        given(embeddings.findFirstChunks(List.of(1L, 2L)))
                .willReturn(List.<Object[]>of(new Object[] {1L, "  Como configurar\n a VPN " + "palavra ".repeat(30)}));

        List<DocumentSummary> recent = service.recent(null, USER_ID);

        assertThat(recent.get(0).excerpt())
                .startsWith("Como configurar a VPN palavra")
                .hasSizeLessThanOrEqualTo(SNIPPET_LENGTH)
                .endsWith("…");
        // A document with no indexed text has no excerpt rather than failing the whole list.
        assertThat(recent.get(1).excerpt()).isNull();
        assertThat(recent.get(0).responsibleName()).isEqualTo("Mariana Costa");
    }

    @Test
    @DisplayName("with no limit, the five most recent indexed documents are asked for")
    void defaultsToFive() {
        // Only approved documents are listed, but that filter lives in the query itself and is
        // checked against the database in RecentDocumentsQueryTest.
        given(documents.findRecent(any(), any(), any())).willReturn(List.of());

        service.recent(null, USER_ID);

        then(documents).should().findRecent(eq(ProcessingStatus.COMPLETED), any(), eq(PageRequest.of(0, 5)));
    }

    @Test
    @DisplayName("only the categories the user's sector sees are listed")
    void listsOnlyTheSectorsCategories() {
        given(documents.findRecent(any(), any(), any())).willReturn(List.of());

        service.recent(null, USER_ID);

        then(documents).should().findRecent(eq(ProcessingStatus.COMPLETED), eq(ALLOWED), any());
    }

    @Test
    @DisplayName("a sector with no category gets an empty list, without querying the documents")
    void sectorWithNoCategoryGetsAnEmptyList() {
        given(categoryAccess.allowedCategoryIds(USER_ID)).willReturn(Set.of());

        assertThat(service.recent(null, USER_ID)).isEmpty();

        then(documents).shouldHaveNoInteractions();
        then(embeddings).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("a limit outside 1..50 is refused before reaching the database")
    void limitMustStayWithinBounds() {
        assertThatThrownBy(() -> service.recent(0, USER_ID)).isInstanceOf(InvalidDocumentListRequestException.class);
        assertThatThrownBy(() -> service.recent(51, USER_ID)).isInstanceOf(InvalidDocumentListRequestException.class);

        then(documents).should(never()).findRecent(any(), any(), any());
    }

    @Test
    @DisplayName("with no page or size, the first 20 documents awaiting approval are asked for")
    void pendingDefaultsToTheFirstTwenty() {
        given(documents.findOldestFirstByStatus(any(), any())).willReturn(Page.empty());

        service.pending(null, null);

        then(documents).should().findOldestFirstByStatus(
                eq(DocumentStatus.PENDING_APPROVAL), eq(PageRequest.of(0, 20)));
    }

    @Test
    @DisplayName("each pending document carries its description, creator, sector, category and suggestion, and the page its totals")
    void pendingDocumentsAreMappedWithTheirPage() {
        Category suggested = new Category("Tecnico", null);
        ReflectionTestUtils.setField(suggested, "id", 3L);
        Document withSuggestion = document(1L);
        withSuggestion.setSuggestedCategory(suggested);
        withSuggestion.setDescription("Manual de operação da turbina");
        withSuggestion.setCategoryConfidence(new BigDecimal("0.870"));
        PageRequest request = PageRequest.of(1, 2);
        given(documents.findOldestFirstByStatus(DocumentStatus.PENDING_APPROVAL, request))
                .willReturn(new PageImpl<>(List.of(withSuggestion, document(2L)), request, 5));

        PageResponse<PendingDocument> page = service.pending(1, 2);

        assertThat(page.content()).extracting(PendingDocument::id).containsExactly(1L, 2L);
        PendingDocument first = page.content().get(0);
        assertThat(first.description()).isEqualTo("Manual de operação da turbina");
        assertThat(first.creatorName()).isEqualTo("Mariana Costa");
        assertThat(first.sectorName()).isEqualTo("Engenharia");
        assertThat(first.category().name()).isEqualTo("Manuais");
        assertThat(first.suggestedCategory()).isEqualTo(new PendingDocument.CategoryRef(3L, "Tecnico"));
        assertThat(first.confidence()).isEqualByComparingTo("0.87");
        assertThat(page.content().get(1).description()).isNull();
        assertThat(page.content().get(1).suggestedCategory()).isNull();
        assertThat(page.content().get(1).confidence()).isNull();
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("a negative page or a size outside 1..50 is refused before reaching the database")
    void pageAndSizeMustStayWithinBounds() {
        assertThatThrownBy(() -> service.pending(-1, null)).isInstanceOf(InvalidDocumentListRequestException.class);
        assertThatThrownBy(() -> service.pending(null, 0)).isInstanceOf(InvalidDocumentListRequestException.class);
        assertThatThrownBy(() -> service.pending(null, 51)).isInstanceOf(InvalidDocumentListRequestException.class);

        then(documents).should(never()).findOldestFirstByStatus(any(), any());
    }
}
