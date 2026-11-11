package com.akpedia.server.repository;

import com.akpedia.server.entity.Category;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByName(String name);

    List<Category> findAllByOrderByNameAsc();

    /**
     * Ids of the categories the user's sector may see, read from {@code sector_categories}.
     *
     * <p>A query rather than {@code user.getSector().getCategories()}: the collection would be
     * whatever the persistence context loaded first, while this goes to the table every time, so a
     * change an admin just saved applies from the very next request.
     */
    @Query("SELECT c.id FROM User u JOIN u.sector s JOIN s.categories c WHERE u.id = :userId")
    List<Long> findIdsVisibleTo(@Param("userId") Long userId);

    /** The categories the user's sector may see, by name. Read the same way as {@link #findIdsVisibleTo}. */
    @Query("SELECT c FROM User u JOIN u.sector s JOIN s.categories c WHERE u.id = :userId ORDER BY c.name ASC")
    List<Category> findVisibleTo(@Param("userId") Long userId);
}
