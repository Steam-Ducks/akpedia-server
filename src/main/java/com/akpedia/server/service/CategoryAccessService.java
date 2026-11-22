package com.akpedia.server.service;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.exception.CategoryForbiddenException;
import com.akpedia.server.repository.CategoryRepository;
import com.akpedia.server.security.Viewer;

/**
 * Which categories a user may see: the ones their sector is given in {@code sector_categories}.
 *
 * <p>The one place that answers it, for every route that shows documents -- search, the recent
 * list, the category catalog and opening a file -- so a category blocked for a sector is blocked
 * everywhere at once. Nothing is cached: each call reads the table, which is what makes a change
 * on the admin's permissions screen apply from the next request.
 *
 * <p>The rule holds for every role, admins included. The two exceptions are in
 * {@link #checkCanOpen} and only concern opening a single document.
 */
@Service
public class CategoryAccessService {

    private final CategoryRepository categories;

    public CategoryAccessService(CategoryRepository categories) {
        this.categories = categories;
    }

    /**
     * Ids of the categories the user may see; empty when their sector sees none.
     *
     * @param userId the user's row
     */
    @Transactional(readOnly = true)
    public Set<Long> allowedCategoryIds(Long userId) {
        return Set.copyOf(categories.findIdsVisibleTo(userId));
    }

    /**
     * The categories the user may see, by name.
     *
     * @param userId the user's row
     */
    @Transactional(readOnly = true)
    public List<Category> allowedCategories(Long userId) {
        return categories.findVisibleTo(userId);
    }

    /**
     * Refuses a document whose category the viewer's sector may not see.
     *
     * <p>Two cases open it anyway, because a flow that already exists depends on each:
     * <ul>
     *   <li>its author, who is shown it among their own submissions -- an approver may have moved
     *       it to a category the author's sector does not see, and it is still their document;</li>
     *   <li>a reviewer, while it awaits approval: the review queue lists every pending document,
     *       whatever its category, and the approver has to read it to decide. Once approved, the
     *       sector rule applies to them like to anyone else.</li>
     * </ul>
     *
     * @throws CategoryForbiddenException if none of that lets the viewer open it
     */
    @Transactional(readOnly = true)
    public void checkCanOpen(Document document, Viewer viewer) {
        if (Objects.equals(document.getCreator().getId(), viewer.userId())) {
            return;
        }
        if (viewer.reviewer() && document.getStatus() == DocumentStatus.PENDING_APPROVAL) {
            return;
        }
        if (!allowedCategoryIds(viewer.userId()).contains(document.getCategory().getId())) {
            throw new CategoryForbiddenException(document.getId());
        }
    }
}
