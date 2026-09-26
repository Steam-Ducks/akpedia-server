package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.akpedia.server.config.SearchProperties;
import com.akpedia.server.dto.DocumentSummary;
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

    private DocumentListService service;

    @BeforeEach
    void setUp() {
        service = new DocumentListService(
                documents, embeddings, new SearchProperties(10, 50, 0.85, SNIPPET_LENGTH));
    }

    private static Document document(long id) {
        User creator = new User("Mariana Costa", "mariana@akpedia.test", "hash", new Sector("Engenharia", null));
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

        List<DocumentSummary> recent = service.recent(null);

        assertThat(recent.get(0).excerpt())
                .startsWith("Como configurar a VPN palavra")
                .hasSizeLessThanOrEqualTo(SNIPPET_LENGTH)
                .endsWith("…");
        // A document with no indexed text has no excerpt rather than failing the whole list.
        assertThat(recent.get(1).excerpt()).isNull();
        assertThat(recent.get(0).responsibleName()).isEqualTo("Mariana Costa");
    }

    @Test
    @DisplayName("with no limit, the five most recent indexed, not archived documents are asked for")
    void defaultsToFive() {
        given(documents.findRecent(any(), any(), any())).willReturn(List.of());

        service.recent(null);

        then(documents).should().findRecent(
                eq(ProcessingStatus.COMPLETED), eq(DocumentStatus.ARCHIVED), eq(PageRequest.of(0, 5)));
    }

    @Test
    @DisplayName("a limit outside 1..50 is refused before reaching the database")
    void limitMustStayWithinBounds() {
        assertThatThrownBy(() -> service.recent(0)).isInstanceOf(InvalidDocumentListRequestException.class);
        assertThatThrownBy(() -> service.recent(51)).isInstanceOf(InvalidDocumentListRequestException.class);

        then(documents).should(never()).findRecent(any(), any(), any());
    }
}
