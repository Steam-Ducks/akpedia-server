package com.akpedia.server.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.dto.PublishTermsRequest;
import com.akpedia.server.dto.TermsAcceptanceResponse;
import com.akpedia.server.dto.TermsStatusResponse;
import com.akpedia.server.dto.TermsVersionResponse;
import com.akpedia.server.entity.TermsAcceptance;
import com.akpedia.server.entity.TermsVersion;
import com.akpedia.server.exception.TermsChangedException;
import com.akpedia.server.repository.TermsAcceptanceRepository;
import com.akpedia.server.repository.TermsVersionRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * Versioned terms of use: admins publish them, every user accepts the current version before
 * using the API. With nothing published yet, nobody is asked anything.
 */
@Service
public class TermsService {

    private final TermsVersionRepository versions;
    private final TermsAcceptanceRepository acceptances;
    private final UserRepository users;

    public TermsService(
            TermsVersionRepository versions, TermsAcceptanceRepository acceptances, UserRepository users) {
        this.versions = versions;
        this.acceptances = acceptances;
        this.users = users;
    }

    /** The current terms and what the user with this Keycloak account id accepted last. */
    @Transactional(readOnly = true)
    public TermsStatusResponse status(String keycloakId) {
        TermsVersionResponse current = versions.findFirstByOrderByVersionDesc()
                .map(TermsVersionResponse::of)
                .orElse(null);
        TermsAcceptanceResponse accepted = latestAcceptance(keycloakId)
                .map(TermsService::toResponse)
                .orElse(null);
        return new TermsStatusResponse(current, accepted);
    }

    /**
     * Whether this account still has to accept the current terms. Looks the user up by their
     * Keycloak id only, so a user without a row yet simply has accepted nothing.
     */
    @Transactional(readOnly = true)
    public boolean mustAccept(String keycloakId) {
        Optional<TermsVersion> current = versions.findFirstByOrderByVersionDesc();
        if (current.isEmpty()) {
            return false;
        }
        int currentVersion = current.get().getVersion();
        return latestAcceptance(keycloakId)
                .map(acceptance -> acceptance.getTermsVersion().getVersion() != currentVersion)
                .orElse(true);
    }

    /**
     * Records that the user accepted {@code version}, which must be the current one.
     *
     * @throws TermsChangedException if another version was published since the user was shown
     *         this one, or none was ever published
     */
    @Transactional
    public TermsAcceptanceResponse accept(Long userId, int version) {
        TermsVersion current = versions.findFirstByOrderByVersionDesc()
                .orElseThrow(() -> new TermsChangedException(version, null));
        if (current.getVersion() != version) {
            throw new TermsChangedException(version, current.getVersion());
        }
        TermsAcceptance acceptance = acceptances.saveAndFlush(
                new TermsAcceptance(users.getReferenceById(userId), current));
        return toResponse(acceptance);
    }

    /** Every published version, newest first. */
    @Transactional(readOnly = true)
    public List<TermsVersionResponse> history() {
        return versions.findAllByOrderByVersionDesc().stream().map(TermsVersionResponse::of).toList();
    }

    /** Publishes the next version. Everyone, the admin included, must accept it on their next request. */
    @Transactional
    public TermsVersionResponse publish(Long publisherId, PublishTermsRequest request) {
        versions.lockForPublishing();
        int next = versions.findFirstByOrderByVersionDesc().map(TermsVersion::getVersion).orElse(0) + 1;
        TermsVersion terms = versions.saveAndFlush(new TermsVersion(
                next, request.title().strip(), request.content().strip(), users.getReferenceById(publisherId)));
        // Loaded again so the publisher's name is there for the answer.
        return TermsVersionResponse.of(versions.findFirstByOrderByVersionDesc().orElse(terms));
    }

    private Optional<TermsAcceptance> latestAcceptance(String keycloakId) {
        return acceptances.findFirstByUserKeycloakIdOrderByAcceptedAtDescIdDesc(keycloakId);
    }

    private static TermsAcceptanceResponse toResponse(TermsAcceptance acceptance) {
        return new TermsAcceptanceResponse(acceptance.getTermsVersion().getVersion(), acceptance.getAcceptedAt());
    }
}
