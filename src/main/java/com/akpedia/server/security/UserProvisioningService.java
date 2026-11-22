package com.akpedia.server.security;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.repository.SectorRepository;
import com.akpedia.server.repository.UserRepository;
import com.akpedia.server.service.ProfileRules;

/** Keeps the {@code users} row of a Keycloak account in step with the account. */
@Service
public class UserProvisioningService {

    /** {@code users.name} is VARCHAR(150). */
    private static final int MAX_NAME_LENGTH = 150;

    private final UserRepository users;
    private final SectorRepository sectors;

    public UserProvisioningService(UserRepository users, SectorRepository sectors) {
        this.users = users;
        this.sectors = sectors;
    }

    /**
     * Finds the account's row, creating it on the first login, and copies over any name, email,
     * sector or CPF changed in Keycloak since.
     *
     * <p>A CPF the row does not have yet (the first access, or a CPF changed in Keycloak) is
     * checked first: its check digits, and that no other user has it. One that fails is left off
     * the row, which still gets everything else, so the user can ask an admin for the right one;
     * the result says what was wrong.
     *
     * <p>A row with the same email but no Keycloak link (the seeded user, say) is taken over
     * rather than duplicated, since the email is unique. So is one linked to another account with
     * that email, which happens when an account is deleted and registered again.
     *
     * <p>A sector the database does not have yet is created: the list of sectors lives in the
     * realm's user profile.
     *
     * @return the user's id, and what is wrong with the account's CPF, if anything
     * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent request
     *         created the same row or sector first; calling again then finds it
     */
    @Transactional
    public ProvisionedUser provision(KeycloakAccount account) {
        User user = users.findByKeycloakId(account.id())
                .or(() -> users.findByEmail(account.email()))
                .orElse(null);
        String name = account.name().length() > MAX_NAME_LENGTH
                ? account.name().substring(0, MAX_NAME_LENGTH)
                : account.name();
        CpfProblem cpfProblem = account.cpf().equals(user == null ? null : user.getCpf())
                ? null
                : cpfProblem(account.cpf(), user);
        Sector sector = sectorNamed(account.sector());

        if (user == null) {
            user = new User(name, account.email(), sector);
            user.setKeycloakId(account.id());
            if (cpfProblem == null) {
                user.setCpf(account.cpf());
            }
            return new ProvisionedUser(users.saveAndFlush(user).getId(), cpfProblem);
        }
        // Set only what changed, so an unchanged user costs no UPDATE on every request.
        if (!account.id().equals(user.getKeycloakId())) {
            user.setKeycloakId(account.id());
        }
        if (!name.equals(user.getName())) {
            user.setName(name);
        }
        if (!account.email().equals(user.getEmail())) {
            user.setEmail(account.email());
        }
        if (!Objects.equals(sector.getId(), user.getSector().getId())) {
            user.setSector(sector);
        }
        if (cpfProblem == null && !account.cpf().equals(user.getCpf())) {
            user.setCpf(account.cpf());
        }
        users.flush();
        return new ProvisionedUser(user.getId(), cpfProblem);
    }

    /**
     * What keeps the CPF off the row: wrong check digits, or another user's row having it.
     *
     * @param user the account's row, {@code null} before the first access
     * @return {@code null} if nothing does
     */
    private CpfProblem cpfProblem(String cpf, User user) {
        if (!ProfileRules.isValidCpf(cpf)) {
            return CpfProblem.INVALID;
        }
        boolean taken = users.findByCpf(cpf).filter(other -> user == null || !other.getId().equals(user.getId()))
                .isPresent();
        return taken ? CpfProblem.TAKEN : null;
    }

    private Sector sectorNamed(String name) {
        return sectors.findByName(name)
                .orElseGet(() -> sectors.saveAndFlush(new Sector(name, null)));
    }
}
