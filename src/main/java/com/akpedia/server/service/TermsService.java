package com.akpedia.server.service;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.dto.OptionalPermission;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.PublishTermsRequest;
import com.akpedia.server.dto.TermsAcceptanceEntry;
import com.akpedia.server.dto.TermsAcceptanceResponse;
import com.akpedia.server.dto.TermsStatusResponse;
import com.akpedia.server.dto.TermsVersionResponse;
import com.akpedia.server.entity.TermsAcceptance;
import com.akpedia.server.entity.TermsVersion;
import com.akpedia.server.exception.InvalidPageRequestException;
import com.akpedia.server.exception.TermsChangedException;
import com.akpedia.server.exception.TermsVersionExistsException;
import com.akpedia.server.repository.TermsAcceptanceRepository;
import com.akpedia.server.repository.TermsVersionRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * Versioned terms of use: admins publish them, every user accepts the current version before
 * using the API. With nothing published yet, nobody is asked anything.
 *
 * <p>Versions are numbered by the server in sequence, {@code 1.0}, {@code 2.0}, {@code 3.0}. The
 * current one is still the newest published, by {@code published_at} and then id: older versions
 * may carry free labels from before the numbering, which are skipped when counting.
 */
@Service
public class TermsService {

    /**
     * A numbered version, {@code X.0}, or {@code X.Y} from before the numbering was only by majors;
     * anything else is a free label and is skipped.
     */
    private static final Pattern NUMBERED = Pattern.compile("(\\d{1,9})\\.\\d{1,9}");

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final TermsVersionRepository versions;
    private final TermsAcceptanceRepository acceptances;
    private final UserRepository users;
    private final AuditService audit;
    private final OptionalPermissionService optionalPermissions;

    public TermsService(TermsVersionRepository versions, TermsAcceptanceRepository acceptances,
            UserRepository users, AuditService audit, OptionalPermissionService optionalPermissions) {
        this.versions = versions;
        this.acceptances = acceptances;
        this.users = users;
        this.audit = audit;
        this.optionalPermissions = optionalPermissions;
    }

    /** The current terms and what the user with this Keycloak account id accepted last. */
    @Transactional(readOnly = true)
    public TermsStatusResponse status(String keycloakId) {
        TermsVersionResponse current = versions.findFirstByOrderByPublishedAtDescIdDesc()
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
        Optional<TermsVersion> current = versions.findFirstByOrderByPublishedAtDescIdDesc();
        if (current.isEmpty()) {
            return false;
        }
        Long currentId = current.get().getId();
        return latestAcceptance(keycloakId)
                .map(acceptance -> !acceptance.getTermsVersion().getId().equals(currentId))
                .orElse(true);
    }

    /**
     * Records that the user accepted {@code version}, which must be the current one.
     *
     * @throws TermsChangedException if another version was published since the user was shown
     *         this one, or none was ever published
     */
    @Transactional
    public TermsAcceptanceResponse accept(Long userId, String version) {
        return accept(userId, version, Map.of());
    }

    /**
     * Records that the user accepted {@code version}, with their choice for each of its optional
     * permissions (any left out is not allowed).
     *
     * @throws TermsChangedException as {@link #accept(Long, String)}
     */
    @Transactional
    public TermsAcceptanceResponse accept(Long userId, String version, Map<String, Boolean> optional) {
        TermsVersion current = versions.findFirstByOrderByPublishedAtDescIdDesc()
                .orElseThrow(() -> new TermsChangedException(version, null));
        // Versions are unique in any case, so this cannot match another one.
        if (!current.getVersion().equalsIgnoreCase(version.strip())) {
            throw new TermsChangedException(version, current.getVersion());
        }
        // Accepting again (a double click, two tabs) answers the first acceptance.
        Optional<TermsAcceptance> existing = acceptances.findByUserIdAndTermsVersionId(userId, current.getId());
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        TermsAcceptance acceptance = acceptances.saveAndFlush(
                new TermsAcceptance(users.getReferenceById(userId), current));
        optionalPermissions.recordAcceptance(userId, current, optional);
        audit.record(userId, AuditService.TERMS_ACCEPTED, AuditService.TERMS_VERSION, current.getId(),
                AuditService.details("version", current.getVersion()));
        return toResponse(acceptance);
    }

    /**
     * Records that the user declined {@code version}; they are logged out right after. Nothing
     * else changes: they will be asked again on their next login.
     *
     * <p>Not refused for a version that is not the current one, or does not exist: the user is
     * leaving either way, and the record keeps the version they were shown.
     */
    @Transactional
    public void decline(String version) {
        Long versionId = versions.findByVersionIgnoreCase(version.strip()).map(TermsVersion::getId).orElse(null);
        audit.record(null, AuditService.TERMS_DECLINED, AuditService.TERMS_VERSION, versionId,
                AuditService.details("version", version));
    }

    /**
     * One page of the acceptances, newest first.
     *
     * @param version only this version (any case), or every one when blank
     * @param user    part of the user's name or e-mail, or every user when blank
     * @throws InvalidPageRequestException if the page is negative or the size out of range
     */
    @Transactional(readOnly = true)
    public PageResponse<TermsAcceptanceEntry> acceptances(String version, String user, Integer requestedPage,
            Integer requestedSize) {
        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPageRequestException(MAX_PAGE_SIZE);
        }
        String versionFilter = version == null ? "" : version.strip();
        String userFilter = user == null || user.isBlank()
                ? ""
                : "%" + likeEscaped(user.strip().toLowerCase(Locale.ROOT)) + "%";
        return PageResponse.from(acceptances.search(versionFilter, userFilter, PageRequest.of(page, size)),
                TermsAcceptanceEntry::of);
    }

    /** {@code %} and {@code _} typed by the admin are dropped rather than read as wildcards. */
    private static String likeEscaped(String text) {
        return text.replace("%", "").replace("_", "");
    }

    /** Every published version, newest first. */
    @Transactional(readOnly = true)
    public List<TermsVersionResponse> history() {
        return versions.findAllByOrderByPublishedAtDescIdDesc().stream().map(TermsVersionResponse::of).toList();
    }

    /**
     * Publishes a new version, numbered the next {@code X.0}. It becomes the current one: everyone,
     * the admin included, must accept it on their next request.
     *
     * @throws TermsVersionExistsException if the number is taken anyway (a version added straight to
     *                                     the database)
     */
    @Transactional
    public TermsVersionResponse publish(Long publisherId, PublishTermsRequest request) {
        // Taken before counting, so two admins publishing at once get consecutive numbers.
        versions.lockForPublishing();
        String version = nextVersion(versions.findAllVersions());
        if (versions.existsByVersionIgnoreCase(version)) {
            throw new TermsVersionExistsException(version);
        }
        TermsVersion terms;
        try {
            terms = versions.saveAndFlush(new TermsVersion(version, request.title().strip(), request.content().strip(),
                    OptionalPermission.toJson(optionalPermissions.forNewVersion(request.optionalPermissionsOrEmpty())),
                    users.getReferenceById(publisherId)));
        } catch (DataIntegrityViolationException e) {
            // Only without the lock (a version added straight to the database): the unique indexes caught it.
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_terms_versions_version")) {
                throw new TermsVersionExistsException(version);
            }
            throw e;
        }
        audit.record(publisherId, AuditService.TERMS_PUBLISHED, AuditService.TERMS_VERSION, terms.getId(),
                AuditService.details("version", version, "title", terms.getTitle()));
        // The publisher is a reference: their name loads here, inside the transaction.
        return TermsVersionResponse.of(terms);
    }

    /**
     * The version after {@code published}: one past the highest major, {@code X.0}. Versions that are
     * not numbered are left out; with none, {@code 1.0}.
     */
    static String nextVersion(Collection<String> published) {
        long major = 0;
        for (String version : published) {
            Matcher numbered = NUMBERED.matcher(version.strip());
            if (numbered.matches()) {
                major = Math.max(major, Long.parseLong(numbered.group(1)));
            }
        }
        return (major + 1) + ".0";
    }

    private Optional<TermsAcceptance> latestAcceptance(String keycloakId) {
        return acceptances.findFirstByUserKeycloakIdOrderByAcceptedAtDescIdDesc(keycloakId);
    }

    private static TermsAcceptanceResponse toResponse(TermsAcceptance acceptance) {
        return new TermsAcceptanceResponse(acceptance.getTermsVersion().getVersion(), acceptance.getAcceptedAt());
    }
}
