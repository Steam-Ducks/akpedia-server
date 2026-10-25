package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.akpedia.server.config.DefaultUserProvider;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.SubmissionStatus;
import com.akpedia.server.dto.SubmissionSummary;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.InvalidDocumentListRequestException;
import com.akpedia.server.exception.UserNotFoundException;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * The rules of the submission listing: which status the author is shown, whose documents are
 * listed, and what a bad request answers.
 */
@ExtendWith(MockitoExtension.class)
class DocumentSubmissionServiceTest {

    private static final long AUTHOR_ID = 20L;

    @Mock
    private DocumentRepository documents;
    @Mock
    private UserRepository users;
    @Mock
    private DefaultUserProvider defaultUser;

    private DocumentSubmissionService service;
    private Category category;
    private User author;

    @BeforeEach
    void setUp() {
        service = new DocumentSubmissionService(documents, users, defaultUser);
        category = new Category("Manuais", "manuais tecnicos");
        author = new User("Ana", "ana@akpedia.test", "hash", new Sector("TI", "setor de TI"));
        ReflectionTestUtils.setField(author, "id", AUTHOR_ID);
    }

    private Document document(long id, DocumentStatus status, String reviewComment) {
        return document(id, status, ProcessingStatus.PENDING, reviewComment);
    }

    private Document document(long id, DocumentStatus status, ProcessingStatus processing, String reviewComment) {
        Document document = new Document("manual.pdf", "application/pdf", 1024L, category, author, status);
        document.setProcessingStatus(processing);
        ReflectionTestUtils.setField(document, "id", id);
        ReflectionTestUtils.setField(document, "createdAt", OffsetDateTime.parse("2026-10-16T11:20:00Z"));
        if (reviewComment != null) {
            document.setReviewComment(reviewComment);
            document.setReviewedAt(OffsetDateTime.parse("2026-10-16T12:00:00Z"));
        }
        return document;
    }

    /** Stubs the repository to answer one page holding exactly these documents. */
    private void givenSubmissions(Document... found) {
        given(users.existsById(AUTHOR_ID)).willReturn(true);
        given(documents.findSubmissions(eq(AUTHOR_ID), any(), anyBoolean(), any(), any()))
                .willReturn(new PageImpl<>(List.of(found), PageRequest.of(0, 10), found.length));
    }

    @Test
    @DisplayName("a document waiting on an approver reads as UNDER_REVIEW")
    void pendingApprovalReadsAsUnderReview() {
        givenSubmissions(document(1L, DocumentStatus.PENDING_APPROVAL, null));

        SubmissionSummary summary = service.submissions(AUTHOR_ID, null, null, null).content().get(0);

        assertThat(summary.submissionStatus()).isEqualTo(SubmissionStatus.UNDER_REVIEW);
        assertThat(summary.reviewComment()).isNull();
    }

    @Test
    @DisplayName("an approved and indexed document reads as PUBLISHED")
    void approvedAndIndexedReadsAsPublished() {
        givenSubmissions(document(2L, DocumentStatus.APPROVED, ProcessingStatus.COMPLETED, null));

        SubmissionSummary summary = service.submissions(AUTHOR_ID, null, null, null).content().get(0);

        assertThat(summary.submissionStatus()).isEqualTo(SubmissionStatus.PUBLISHED);
        assertThat(summary.reviewComment()).isNull();
    }

    @Test
    @DisplayName("an approved document still being indexed reads as PROCESSING, not PUBLISHED")
    void approvedButNotIndexedReadsAsProcessing() {
        // Search cannot reach it yet; PUBLISHED would have the author tell colleagues to look for
        // something they will not find.
        givenSubmissions(
                document(5L, DocumentStatus.APPROVED, ProcessingStatus.PENDING, null),
                document(6L, DocumentStatus.APPROVED, ProcessingStatus.PROCESSING, null));

        List<SubmissionSummary> found = service.submissions(AUTHOR_ID, null, null, null).content();

        assertThat(found).extracting(SubmissionSummary::submissionStatus)
                .containsOnly(SubmissionStatus.PROCESSING);
    }

    @Test
    @DisplayName("an approved document whose indexing failed reads as INDEXING_FAILED, not PROCESSING")
    void approvedButIndexingFailedReadsAsIndexingFailed() {
        // PROCESSING would promise the document will show up in search, and it will not.
        givenSubmissions(document(7L, DocumentStatus.APPROVED, ProcessingStatus.FAILED, null));

        SubmissionSummary summary = service.submissions(AUTHOR_ID, null, null, null).content().get(0);

        assertThat(summary.submissionStatus()).isEqualTo(SubmissionStatus.INDEXING_FAILED);
        assertThat(summary.reviewComment()).isNull();
    }

    @Test
    @DisplayName("a rejected document reads as RETURNED and carries the review comment")
    void rejectedReadsAsReturnedWithTheComment() {
        givenSubmissions(document(3L, DocumentStatus.REJECTED, "Falta a secao de revisao A."));

        SubmissionSummary summary = service.submissions(AUTHOR_ID, null, null, null).content().get(0);

        assertThat(summary.submissionStatus()).isEqualTo(SubmissionStatus.RETURNED);
        assertThat(summary.reviewComment()).isEqualTo("Falta a secao de revisao A.");
        assertThat(summary.reviewedAt()).isNotNull();
    }

    @Test
    @DisplayName("a comment left on a document that was then approved is not shown as a return")
    void commentOnAnApprovedDocumentIsNotShown() {
        // The approver commented and approved anyway. Showing the comment beside PUBLISHED would
        // read as if the document were still waiting on a fix.
        givenSubmissions(document(4L, DocumentStatus.APPROVED, ProcessingStatus.COMPLETED,
                "Ajuste o titulo na proxima versao."));

        SubmissionSummary summary = service.submissions(AUTHOR_ID, null, null, null).content().get(0);

        assertThat(summary.submissionStatus()).isEqualTo(SubmissionStatus.PUBLISHED);
        assertThat(summary.reviewComment()).isNull();
        assertThat(summary.reviewedAt()).isNull();
    }

    @Test
    @DisplayName("only submitted statuses are asked for: a draft or an archived document is not a submission")
    void onlySubmittedStatusesAreQueried() {
        givenSubmissions();

        service.submissions(AUTHOR_ID, null, null, null);

        ArgumentCaptor<List<DocumentStatus>> statuses = ArgumentCaptor.captor();
        verify(documents).findSubmissions(eq(AUTHOR_ID), statuses.capture(), anyBoolean(), any(), any());
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(
                        DocumentStatus.PENDING_APPROVAL, DocumentStatus.APPROVED, DocumentStatus.REJECTED)
                .doesNotContain(DocumentStatus.DRAFT, DocumentStatus.ARCHIVED);
    }

    @Test
    @DisplayName("without a creatorId the configured default user is listed")
    void noCreatorIdFallsBackToTheDefaultUser() {
        given(defaultUser.userId()).willReturn(99L);
        given(users.existsById(99L)).willReturn(true);
        given(documents.findSubmissions(eq(99L), any(), anyBoolean(), any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        service.submissions(null, null, null, null);

        verify(documents).findSubmissions(eq(99L), any(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("an explicit creatorId does not consult the default user")
    void explicitCreatorIdIgnoresTheDefaultUser() {
        givenSubmissions();

        service.submissions(AUTHOR_ID, null, null, null);

        verifyNoInteractions(defaultUser);
    }

    @Test
    @DisplayName("a creatorId no user has answers 404 instead of an empty page")
    void unknownCreatorIsRejected() {
        given(users.existsById(404L)).willReturn(false);

        // An empty page would read as "you have submitted nothing", which is a different answer.
        assertThatThrownBy(() -> service.submissions(404L, null, null, null))
                .isInstanceOf(UserNotFoundException.class);

        verify(documents, never()).findSubmissions(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("page and size default to the first page of ten")
    void pagingDefaults() {
        givenSubmissions();

        service.submissions(AUTHOR_ID, null, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.captor();
        verify(documents).findSubmissions(eq(AUTHOR_ID), any(), anyBoolean(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(DocumentSubmissionService.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("the requested page and size reach the query")
    void pagingIsPassedThrough() {
        given(users.existsById(AUTHOR_ID)).willReturn(true);
        given(documents.findSubmissions(eq(AUTHOR_ID), any(), anyBoolean(), any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(2, 5), 0));

        service.submissions(AUTHOR_ID, 2, 5, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.captor();
        verify(documents).findSubmissions(eq(AUTHOR_ID), any(), anyBoolean(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("the response carries the totals a screen needs to page through")
    void responseCarriesTheTotals() {
        given(users.existsById(AUTHOR_ID)).willReturn(true);
        given(documents.findSubmissions(eq(AUTHOR_ID), any(), anyBoolean(), any(), any())).willReturn(
                new PageImpl<>(List.of(document(1L, DocumentStatus.APPROVED, null)), PageRequest.of(1, 10), 23));

        PageResponse<SubmissionSummary> response = service.submissions(AUTHOR_ID, 1, 10, null);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(23);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.content()).hasSize(1);
    }

    @Test
    @DisplayName("a page or size outside its bounds is refused before reaching the database")
    void pagingMustStayWithinBounds() {
        assertThatThrownBy(() -> service.submissions(AUTHOR_ID, -1, null, null))
                .isInstanceOf(InvalidDocumentListRequestException.class);
        assertThatThrownBy(() -> service.submissions(AUTHOR_ID, null, 0, null))
                .isInstanceOf(InvalidDocumentListRequestException.class);
        assertThatThrownBy(() -> service.submissions(AUTHOR_ID, null, DocumentSubmissionService.MAX_SIZE + 1, null))
                .isInstanceOf(InvalidDocumentListRequestException.class);

        verifyNoInteractions(documents, users, defaultUser);
    }

    @Test
    @DisplayName("no categoryIds asks the query for every category")
    void noCategoryIdsFiltersNothing() {
        givenSubmissions();

        service.submissions(AUTHOR_ID, null, null, null);

        verify(documents).findSubmissions(eq(AUTHOR_ID), any(), eq(false), any(), any());
    }

    @Test
    @DisplayName("an empty categoryIds list is the same as none")
    void emptyCategoryIdsFiltersNothing() {
        givenSubmissions();

        service.submissions(AUTHOR_ID, null, null, List.of());

        verify(documents).findSubmissions(eq(AUTHOR_ID), any(), eq(false), any(), any());
    }

    @Test
    @DisplayName("categoryIds reaches the query, with hasCategories set")
    void categoryIdsReachTheQuery() {
        given(users.existsById(AUTHOR_ID)).willReturn(true);
        given(documents.findSubmissions(eq(AUTHOR_ID), any(), eq(true), any(), any()))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        service.submissions(AUTHOR_ID, null, null, List.of(1L, 3L));

        ArgumentCaptor<Collection<Long>> categoryIds = ArgumentCaptor.captor();
        verify(documents).findSubmissions(eq(AUTHOR_ID), any(), eq(true), categoryIds.capture(), any());
        assertThat(categoryIds.getValue()).containsExactlyInAnyOrder(1L, 3L);
    }
}
