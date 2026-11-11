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

import com.akpedia.server.TestCpfs;
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
        return new KeycloakAccount(UUID.randomUUID().toString(), email, name, sector, TestCpfs.random());
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

        User user = reload(provisioning.provision(account).id());

        assertThat(user.getKeycloakId()).isEqualTo(account.id());
        assertThat(user.getEmail()).isEqualTo(account.email());
        assertThat(user.getName()).isEqualTo("Ana Souza");
        assertThat(user.getSector().getName()).isEqualTo(sector);
        assertThat(user.getCpf()).isEqualTo(account.cpf());
        assertThat(user.isActive()).isTrue();
    }

    @Test
    @DisplayName("later logins find the same user instead of creating another")
    void laterLoginsFindTheSameUser() {
        KeycloakAccount account = account(unique("ana") + "@akpedia.test", "Ana", unique("Setor"));
        long before = users.count();

        Long first = provisioning.provision(account).id();
        Long second = provisioning.provision(account).id();

        assertThat(second).isEqualTo(first);
        assertThat(users.count()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("users of the same sector share its row")
    void sameSectorIsShared() {
        String sector = unique("Setor");

        User ana = reload(provisioning.provision(account(unique("ana") + "@akpedia.test", "Ana", sector)).id());
        User bia = reload(provisioning.provision(account(unique("bia") + "@akpedia.test", "Bia", sector)).id());

        assertThat(bia.getSector().getId()).isEqualTo(ana.getSector().getId());
    }

    @Test
    @DisplayName("a name, email, sector or CPF changed in Keycloak is copied over")
    void changesInKeycloakAreCopied() {
        KeycloakAccount before = account(unique("ana") + "@akpedia.test", "Ana", unique("Setor"));
        Long id = provisioning.provision(before).id();

        KeycloakAccount after = new KeycloakAccount(
                before.id(), unique("ana.souza") + "@akpedia.test", "Ana Souza", unique("Outro"), TestCpfs.random());
        assertThat(provisioning.provision(after).id()).isEqualTo(id);

        User user = reload(id);
        assertThat(user.getEmail()).isEqualTo(after.email());
        assertThat(user.getName()).isEqualTo("Ana Souza");
        assertThat(user.getSector().getName()).isEqualTo(after.sector());
        assertThat(user.getCpf()).isEqualTo(after.cpf());
    }

    @Test
    @DisplayName("an existing user with the same email is linked rather than duplicated")
    void existingEmailIsLinked() {
        // Seeded by V5, with no Keycloak account.
        Long seeded = users.findByEmail("aprovador@akpedia.local").orElseThrow().getId();
        KeycloakAccount account = account("aprovador@akpedia.local", "Aprovador", "Engenharia");

        assertThat(provisioning.provision(account).id()).isEqualTo(seeded);
        assertThat(reload(seeded).getKeycloakId()).isEqualTo(account.id());
    }

    @Test
    @DisplayName("a name longer than the column is cut to fit")
    void longNameIsCut() {
        String name = "A".repeat(200);

        KeycloakAccount account = account(unique("ana") + "@akpedia.test", name, unique("Setor"));

        User user = reload(provisioning.provision(account).id());

        assertThat(user.getName()).hasSize(150);
    }

    @Test
    @DisplayName("a first access with a CPF of wrong check digits creates the row without it, and says why")
    void invalidCpfIsLeftOff() {
        KeycloakAccount account = new KeycloakAccount(UUID.randomUUID().toString(), unique("ana") + "@akpedia.test",
                "Ana", unique("Setor"), "52998224726");

        ProvisionedUser provisioned = provisioning.provision(account);

        assertThat(provisioned.cpfProblem()).isEqualTo(CpfProblem.INVALID);
        User user = reload(provisioned.id());
        assertThat(user.getKeycloakId()).isEqualTo(account.id());
        assertThat(user.getCpf()).isNull();
    }

    @Test
    @DisplayName("a first access with another user's CPF creates the row without it, and says why")
    void takenCpfIsLeftOff() {
        KeycloakAccount ana = account(unique("ana") + "@akpedia.test", "Ana", unique("Setor"));
        provisioning.provision(ana);
        KeycloakAccount bia = new KeycloakAccount(UUID.randomUUID().toString(), unique("bia") + "@akpedia.test",
                "Bia", ana.sector(), ana.cpf());

        ProvisionedUser provisioned = provisioning.provision(bia);

        assertThat(provisioned.cpfProblem()).isEqualTo(CpfProblem.TAKEN);
        assertThat(reload(provisioned.id()).getCpf()).isNull();
    }

    @Test
    @DisplayName("a CPF changed in Keycloak to another user's is refused, and the row keeps its own")
    void changedToTakenCpfIsRefused() {
        KeycloakAccount ana = account(unique("ana") + "@akpedia.test", "Ana", unique("Setor"));
        KeycloakAccount bia = account(unique("bia") + "@akpedia.test", "Bia", ana.sector());
        provisioning.provision(ana);
        Long biaId = provisioning.provision(bia).id();
        KeycloakAccount biaWithAnasCpf = new KeycloakAccount(bia.id(), bia.email(), bia.name(), bia.sector(),
                ana.cpf());

        assertThat(provisioning.provision(biaWithAnasCpf).cpfProblem()).isEqualTo(CpfProblem.TAKEN);
        assertThat(reload(biaId).getCpf()).isEqualTo(bia.cpf());
    }

    @Test
    @DisplayName("once an admin puts a valid CPF on the account, the next request takes it on")
    void correctedCpfIsTakenOn() {
        KeycloakAccount wrong = new KeycloakAccount(UUID.randomUUID().toString(), unique("ana") + "@akpedia.test",
                "Ana", unique("Setor"), "52998224726");
        Long id = provisioning.provision(wrong).id();
        KeycloakAccount corrected = new KeycloakAccount(wrong.id(), wrong.email(), wrong.name(), wrong.sector(),
                TestCpfs.random());

        ProvisionedUser provisioned = provisioning.provision(corrected);

        assertThat(provisioned).isEqualTo(new ProvisionedUser(id, null));
        assertThat(reload(id).getCpf()).isEqualTo(corrected.cpf());
    }
}
