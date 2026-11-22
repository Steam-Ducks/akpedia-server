package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.exception.CategoryForbiddenException;
import com.akpedia.server.repository.CategoryRepository;
import com.akpedia.server.security.Viewer;

/**
 * The sector rule on its own: which categories a user may see, and who may open a document anyway.
 * That the ids really come from {@code sector_categories} is checked against the database in
 * {@code CategoryAccessQueryTest}.
 */
@ExtendWith(MockitoExtension.class)
class CategoryAccessServiceTest {

    private static final long VIEWER_ID = 20L;
    private static final long AUTHOR_ID = 30L;
    private static final long ALLOWED = 1L;
    private static final long BLOCKED = 2L;

    @Mock
    private CategoryRepository categories;

    private CategoryAccessService access;

    @BeforeEach
    void setUp() {
        access = new CategoryAccessService(categories);
    }

    private static Document document(long categoryId, DocumentStatus status) {
        Category category = new Category("Categoria " + categoryId, null);
        ReflectionTestUtils.setField(category, "id", categoryId);
        User author = new User("Autora", "autora@akpedia.test", new Sector("Juridico", null));
        ReflectionTestUtils.setField(author, "id", AUTHOR_ID);
        Document document = new Document("contrato.pdf", "application/pdf", 10L, category, author, status);
        ReflectionTestUtils.setField(document, "id", 7L);
        return document;
    }

    private void viewerSees(Long... categoryIds) {
        given(categories.findIdsVisibleTo(VIEWER_ID)).willReturn(List.of(categoryIds));
    }

    private static Viewer employee() {
        return new Viewer(VIEWER_ID, false);
    }

    private static Viewer reviewer() {
        return new Viewer(VIEWER_ID, true);
    }

    @Test
    @DisplayName("the allowed ids are the ones the user's sector is given")
    void allowedIdsComeFromTheSector() {
        viewerSees(ALLOWED, 5L);

        assertThat(access.allowedCategoryIds(VIEWER_ID)).containsExactlyInAnyOrder(ALLOWED, 5L);
    }

    @Test
    @DisplayName("nothing is cached: each call reads the sector's categories again, so a change applies at once")
    void eachCallReadsTheTableAgain() {
        given(categories.findIdsVisibleTo(VIEWER_ID))
                .willReturn(List.of(ALLOWED, BLOCKED))
                .willReturn(List.of(ALLOWED));

        assertThat(access.allowedCategoryIds(VIEWER_ID)).contains(BLOCKED);
        assertThat(access.allowedCategoryIds(VIEWER_ID)).doesNotContain(BLOCKED);
        then(categories).should(times(2)).findIdsVisibleTo(VIEWER_ID);
    }

    @Test
    @DisplayName("a sector with no category sees none")
    void sectorWithNoCategorySeesNone() {
        viewerSees();

        assertThat(access.allowedCategoryIds(VIEWER_ID)).isEmpty();
    }

    @Test
    @DisplayName("a document in an allowed category opens")
    void allowedCategoryOpens() {
        viewerSees(ALLOWED);

        assertThatCode(() -> access.checkCanOpen(document(ALLOWED, DocumentStatus.APPROVED), employee()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a document in a blocked category is refused with category_forbidden")
    void blockedCategoryIsRefused() {
        viewerSees(ALLOWED);

        assertThatThrownBy(() -> access.checkCanOpen(document(BLOCKED, DocumentStatus.APPROVED), employee()))
                .isInstanceOf(CategoryForbiddenException.class)
                .hasMessageContaining("7");
    }

    @Test
    @DisplayName("the rule holds for reviewers too once the document is approved")
    void reviewerIsBoundByTheRuleOutsideTheQueue() {
        viewerSees(ALLOWED);

        assertThatThrownBy(() -> access.checkCanOpen(document(BLOCKED, DocumentStatus.APPROVED), reviewer()))
                .isInstanceOf(CategoryForbiddenException.class);
    }

    @Test
    @DisplayName("a reviewer opens a document awaiting approval in any category, since the queue lists them all")
    void reviewerOpensAPendingDocumentInAnyCategory() {
        assertThatCode(() -> access.checkCanOpen(document(BLOCKED, DocumentStatus.PENDING_APPROVAL), reviewer()))
                .doesNotThrowAnyException();
        then(categories).should(never()).findIdsVisibleTo(VIEWER_ID);
    }

    @Test
    @DisplayName("someone who does not review gets no such exception for a pending document")
    void employeeIsRefusedAPendingDocumentInABlockedCategory() {
        viewerSees(ALLOWED);

        assertThatThrownBy(() -> access.checkCanOpen(document(BLOCKED, DocumentStatus.PENDING_APPROVAL), employee()))
                .isInstanceOf(CategoryForbiddenException.class);
    }

    @Test
    @DisplayName("the author opens their own document, even in a category their sector does not see")
    void authorOpensTheirOwnDocument() {
        assertThatCode(() -> access.checkCanOpen(
                        document(BLOCKED, DocumentStatus.APPROVED), new Viewer(AUTHOR_ID, false)))
                .doesNotThrowAnyException();
    }
}
