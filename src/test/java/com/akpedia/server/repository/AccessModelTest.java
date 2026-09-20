package com.akpedia.server.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Permission;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Cadeia de autorizacao do modelo: usuario -> setor -> categorias -> permissoes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccessModelTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SectorRepository sectorRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Test
    @DisplayName("o setor de um usuario chega ate as permissoes das suas categorias")
    void permissionsAreReachableFromTheUserSector() {
        Permission approve = permissionRepository.findByName("DOCUMENT_APPROVE").orElseThrow();
        Permission view = permissionRepository.findByName("DOCUMENT_VIEW").orElseThrow();

        Category category = TestFixtures.category();
        category.setPermissions(Set.of(approve, view));
        entityManager.persist(category);

        Sector sector = TestFixtures.sector();
        sector.setCategories(Set.of(category));
        entityManager.persist(sector);

        User user = entityManager.persist(TestFixtures.user(sector));
        entityManager.flush();
        entityManager.clear();

        User found = userRepository.findById(user.getId()).orElseThrow();
        Set<Permission> permissions = found.getSector().getCategories().iterator().next().getPermissions();

        assertThat(permissions).extracting(Permission::getName)
                .containsExactlyInAnyOrder("DOCUMENT_APPROVE", "DOCUMENT_VIEW");
    }

    @Test
    @DisplayName("busca de usuario por email")
    void findsUserByEmail() {
        Sector sector = entityManager.persist(TestFixtures.sector());
        User user = entityManager.persist(TestFixtures.user(sector));
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findByEmail(user.getEmail())).isPresent();
        assertThat(userRepository.existsByEmail(user.getEmail())).isTrue();
        assertThat(userRepository.existsByEmail("inexistente@akpedia.test")).isFalse();
    }

    @Test
    @DisplayName("email duplicado viola a restricao UNIQUE")
    void duplicatedEmailIsRejected() {
        Sector sector = entityManager.persist(TestFixtures.sector());
        User first = entityManager.persist(TestFixtures.user(sector));
        entityManager.flush();

        User duplicated = TestFixtures.user(sector);
        duplicated.setEmail(first.getEmail());

        assertThatThrownBy(() -> userRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("usuario nasce ativo")
    void userIsActiveByDefault() {
        Sector sector = entityManager.persist(TestFixtures.sector());
        User user = entityManager.persist(TestFixtures.user(sector));
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.findById(user.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("setor duplicado viola a restricao UNIQUE do nome")
    void duplicatedSectorNameIsRejected() {
        Sector first = entityManager.persist(TestFixtures.sector());
        entityManager.flush();

        Sector duplicated = new Sector(first.getName(), "outro setor");

        assertThatThrownBy(() -> sectorRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("busca de setor por nome")
    void findsSectorByName() {
        Sector sector = entityManager.persist(TestFixtures.sector());
        entityManager.flush();
        entityManager.clear();

        assertThat(sectorRepository.findByName(sector.getName())).isPresent();
    }
}
