package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import com.akpedia.server.dto.CategoryResponse;
import com.akpedia.server.dto.SectorCategories;
import com.akpedia.server.dto.SectorCategoryMatrix;
import com.akpedia.server.entity.AuditLog;
import com.akpedia.server.exception.CategoryNotFoundException;
import com.akpedia.server.exception.SectorNotFoundException;
import com.akpedia.server.repository.AuditLogRepository;
import com.akpedia.server.repository.CategoryRepository;

/** Runs on the migrated database: V13 seeds the sectors and their categories. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SectorPermissionService.class, AuditService.class, AccountDeletionLog.class})
class SectorPermissionServiceTest {

    @Autowired
    private SectorPermissionService permissions;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private AuditLogRepository auditLogs;

    @Autowired
    private TestEntityManager entityManager;

    private Long categoryId(String name) {
        return categories.findByName(name).orElseThrow().getId();
    }

    private SectorCategories row(SectorCategoryMatrix matrix, String sector) {
        return matrix.sectors().stream().filter(row -> row.name().equals(sector)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the matrix has the realm's seven sectors, in its order, with the agreed categories")
    void matrixStartsWithTheAgreedTable() {
        SectorCategoryMatrix matrix = permissions.matrix();

        assertThat(matrix.sectors()).extracting(SectorCategories::name).containsExactlyElementsOf(AdminUserService.SECTORS);
        assertThat(matrix.categories()).extracting(CategoryResponse::name)
                .contains("Juridico", "Qualidade", "Regulatorio", "Tecnico");
        assertThat(row(matrix, "Manutenção").categoryIds()).containsExactlyInAnyOrder(
                categoryId("Tecnico"), categoryId("Regulatorio"), categoryId("Qualidade"));
        assertThat(row(matrix, "Recursos Humanos").categoryIds()).containsExactlyInAnyOrder(
                categoryId("Regulatorio"), categoryId("Juridico"));
    }

    @Test
    @DisplayName("setting a sector's categories replaces them and records before and after")
    void setsCategories() {
        Long sectorId = row(permissions.matrix(), "Financeiro").id();

        SectorCategories updated = permissions.setCategories(sectorId, List.of(categoryId("Tecnico")));
        entityManager.flush();
        entityManager.clear();

        assertThat(updated.categoryIds()).containsExactly(categoryId("Tecnico"));
        assertThat(row(permissions.matrix(), "Financeiro").categoryIds()).containsExactly(categoryId("Tecnico"));
        AuditLog entry = auditLogs.findAll().stream()
                .filter(log -> "SECTOR_CATEGORIES_CHANGED".equals(log.getAction()) && sectorId.equals(log.getEntityId()))
                .findFirst().orElseThrow();
        assertThat(entry.getDetails().replace(" ", ""))
                .contains("\"before\":[\"Juridico\",\"Regulatorio\"]")
                .contains("\"after\":[\"Tecnico\"]");
    }

    @Test
    @DisplayName("setting the same categories again changes and records nothing")
    void sameCategoriesRecordNothing() {
        SectorCategories engineering = row(permissions.matrix(), "Engenharia");
        long before = auditLogs.count();

        permissions.setCategories(engineering.id(), engineering.categoryIds());

        assertThat(auditLogs.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("an unknown sector or category is refused")
    void unknownIdsAreRefused() {
        Long sectorId = row(permissions.matrix(), "Jurídico").id();

        assertThatThrownBy(() -> permissions.setCategories(-1L, List.of())).isInstanceOf(SectorNotFoundException.class);
        assertThatThrownBy(() -> permissions.setCategories(sectorId, List.of(-5L)))
                .isInstanceOf(CategoryNotFoundException.class);
    }
}
