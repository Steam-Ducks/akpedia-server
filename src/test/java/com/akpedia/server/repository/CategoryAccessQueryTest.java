package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

/** Which categories a user may see, read from {@code sector_categories} through their sector. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CategoryAccessQueryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Category alpha;
    private Category beta;
    private Category other;
    private Sector sector;
    private User user;

    @BeforeEach
    void setUp() {
        // Named so the expected order by name is known, whatever the random suffix.
        beta = entityManager.persist(new Category(TestFixtures.unique("b-categoria"), null));
        alpha = entityManager.persist(new Category(TestFixtures.unique("a-categoria"), null));
        other = entityManager.persist(TestFixtures.category());
        sector = TestFixtures.sector();
        sector.setCategories(new LinkedHashSet<>(List.of(beta, alpha)));
        sector = entityManager.persist(sector);
        user = entityManager.persist(TestFixtures.user(sector));

        Sector otherSector = TestFixtures.sector();
        otherSector.setCategories(new LinkedHashSet<>(List.of(other)));
        entityManager.persist(otherSector);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("the ids are exactly the categories of the user's own sector")
    void idsAreTheSectorsCategories() {
        assertThat(categoryRepository.findIdsVisibleTo(user.getId()))
                .containsExactlyInAnyOrder(alpha.getId(), beta.getId());
    }

    @Test
    @DisplayName("the categories come ordered by name, for the search filter")
    void categoriesComeOrderedByName() {
        assertThat(categoryRepository.findVisibleTo(user.getId()))
                .extracting(Category::getId)
                .containsExactly(alpha.getId(), beta.getId());
    }

    @Test
    @DisplayName("a user whose sector has no category sees none")
    void sectorWithNoCategorySeesNone() {
        User lonely = entityManager.persist(TestFixtures.user(entityManager.persist(TestFixtures.sector())));
        entityManager.flush();

        assertThat(categoryRepository.findIdsVisibleTo(lonely.getId())).isEmpty();
        assertThat(categoryRepository.findVisibleTo(lonely.getId())).isEmpty();
    }

    @Test
    @DisplayName("an unknown user sees none, rather than failing")
    void unknownUserSeesNone() {
        assertThat(categoryRepository.findIdsVisibleTo(-1L)).isEmpty();
    }

    @Test
    @DisplayName("a change to sector_categories shows on the very next read, even with the sector already loaded")
    void changeIsReadAtOnce() {
        // Loaded first, so a read through the entity's collection would answer the old categories.
        Sector loaded = entityManager.find(Sector.class, sector.getId());
        assertThat(loaded.getCategories()).hasSize(2);

        jdbc.update("DELETE FROM sector_categories WHERE sector_id = ? AND category_id = ?", sector.getId(), beta.getId());
        jdbc.update("INSERT INTO sector_categories (sector_id, category_id) VALUES (?, ?)", sector.getId(), other.getId());

        assertThat(Set.copyOf(categoryRepository.findIdsVisibleTo(user.getId())))
                .isEqualTo(Set.of(alpha.getId(), other.getId()));
    }
}
