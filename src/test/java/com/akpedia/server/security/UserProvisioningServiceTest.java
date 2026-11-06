package com.akpedia.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import com.akpedia.server.entity.User;
import com.akpedia.server.repository.UserRepository;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserProvisioningService.class)
class UserProvisioningServiceTest {

    @Autowired
    private UserProvisioningService provisioning;

    @Autowired
    private UserRepository users;

    @Autowired
    private TestEntityManager entityManager;

    private static KeycloakAccount account(String email, String name, String sector) {
        return new KeycloakAccount(UUID.randomUUID().toString(), email, name, sector);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private User reload(Long id) {
        entityManager.clear();
        return users.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("the first login creates the user, linked to the Keycloak account, and their sector")
    void firstLoginCreatesUserAndSector() {
        String sector = unique("Setor");
        KeycloakAccount account = account(unique("ana") + "@akpedia.test", "Ana Souza", sector);

        User user = reload(provisioning.provision(account));

        assertThat(user.getKeycloakId()).isEqualTo(account.id());
        assertThat(user.getEmail()).isEqualTo(account.email());
        assertThat(user.getName()).isEqualTo("Ana Souza");
        assertThat(user.getSector().getName()).isEqualTo(sector);
        assertThat(user.isActive()).isTrue();
    }

    @Test
    @DisplayName("later logins find the same user instead of creating another")
    void laterLoginsFindTheSameUser() {
        KeycloakAccount account = account(unique("ana") + "@akpedia.test", "Ana", unique("Setor"));
        long before = users.count();

        Long first = provisioning.provision(account);
        Long second = provisioning.provision(account);

        assertThat(second).isEqualTo(first);
        assertThat(users.count()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("users of the same sector share its row")
    void sameSectorIsShared() {
        String sector = unique("Setor");

        User ana = reload(provisioning.provision(account(unique("ana") + "@akpedia.test", "Ana", sector)));
        User bia = reload(provisioning.provision(account(unique("bia") + "@akpedia.test", "Bia", sector)));

        assertThat(bia.getSector().getId()).isEqualTo(ana.getSector().getId());
    }

    @Test
    @DisplayName("a name, email or sector changed in Keycloak is copied over")
    void changesInKeycloakAreCopied() {
        KeycloakAccount before = account(unique("ana") + "@akpedia.test", "Ana", unique("Setor"));
        Long id = provisioning.provision(before);

        KeycloakAccount after = new KeycloakAccount(
                before.id(), unique("ana.souza") + "@akpedia.test", "Ana Souza", unique("Outro"));
        assertThat(provisioning.provision(after)).isEqualTo(id);

        User user = reload(id);
        assertThat(user.getEmail()).isEqualTo(after.email());
        assertThat(user.getName()).isEqualTo("Ana Souza");
        assertThat(user.getSector().getName()).isEqualTo(after.sector());
    }

    @Test
    @DisplayName("an existing user with the same email is linked rather than duplicated")
    void existingEmailIsLinked() {
        // Seeded by V5, with no Keycloak account.
        Long seeded = users.findByEmail("aprovador@akpedia.local").orElseThrow().getId();
        KeycloakAccount account = account("aprovador@akpedia.local", "Aprovador", "Engenharia");

        assertThat(provisioning.provision(account)).isEqualTo(seeded);
        assertThat(reload(seeded).getKeycloakId()).isEqualTo(account.id());
    }

    @Test
    @DisplayName("a name longer than the column is cut to fit")
    void longNameIsCut() {
        String name = "A".repeat(200);

        User user = reload(provisioning.provision(account(unique("ana") + "@akpedia.test", name, unique("Setor"))));

        assertThat(user.getName()).hasSize(150);
    }
}
