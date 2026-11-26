package com.akpedia.server.service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.dto.OptionalPermission;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.TermsHistoryDetail;
import com.akpedia.server.dto.TermsHistoryEntry;
import com.akpedia.server.entity.AuditLog;
import com.akpedia.server.entity.OptionalPermissionChoice;
import com.akpedia.server.entity.TermsAcceptance;
import com.akpedia.server.entity.TermsVersion;
import com.akpedia.server.entity.User;
import com.akpedia.server.exception.InvalidPageRequestException;
import com.akpedia.server.exception.TermsHistoryEntryNotFoundException;
import com.akpedia.server.repository.AuditLogRepository;
import com.akpedia.server.repository.OptionalPermissionChoiceRepository;
import com.akpedia.server.repository.TermsAcceptanceRepository;
import com.akpedia.server.repository.TermsHistoryRepository;
import com.akpedia.server.repository.TermsVersionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What each user did with each version of the terms, for the admin: accepted it, changed an
 * optional permission afterwards (on their account page), or declined it. Each entry opens on the
 * optional permissions of its version, as the user had them right before it and right after it.
 */
@Service
public class TermsHistoryService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TermsHistoryRepository history;
    private final TermsAcceptanceRepository acceptances;
    private final AuditLogRepository logs;
    private final TermsVersionRepository versions;
    private final OptionalPermissionChoiceRepository choices;

    public TermsHistoryService(TermsHistoryRepository history, TermsAcceptanceRepository acceptances,
            AuditLogRepository logs, TermsVersionRepository versions, OptionalPermissionChoiceRepository choices) {
        this.history = history;
        this.acceptances = acceptances;
        this.logs = logs;
        this.versions = versions;
        this.choices = choices;
    }

    /**
     * One page of the history, newest first.
     *
     * @param version only this version (any case), or every one when blank
     * @param user    part of the user's name or e-mail, or every user when blank
     * @throws InvalidPageRequestException if the page is negative or the size out of range
     */
    @Transactional(readOnly = true)
    public PageResponse<TermsHistoryEntry> search(String version, String user, Integer requestedPage,
            Integer requestedSize) {
        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPageRequestException(MAX_PAGE_SIZE);
        }
        String versionFilter = version == null ? "" : version.strip();
        String userFilter = user == null || user.isBlank()
                ? ""
                : "%" + user.strip().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
        long total = history.count(versionFilter, userFilter);
        return new PageResponse<>(history.search(versionFilter, userFilter, page, size), page, size, total,
                (int) ((total + size - 1) / size));
    }

    /**
     * One entry with the optional permissions of its version.
     *
     * @param action {@code accepted}, {@code updated} or {@code declined}, as {@link #search} answers it
     * @throws TermsHistoryEntryNotFoundException if there is no such entry
     */
    @Transactional(readOnly = true)
    public TermsHistoryDetail detail(String action, Long id) {
        return switch (action) {
            case TermsHistoryEntry.ACCEPTED -> accepted(id);
            case TermsHistoryEntry.UPDATED -> fromAudit(action, id, AuditService.OPTIONAL_PERMISSION_CHOSEN);
            case TermsHistoryEntry.DECLINED -> fromAudit(action, id, AuditService.TERMS_DECLINED);
            default -> throw new TermsHistoryEntryNotFoundException(action, id);
        };
    }

    /**
     * Before: what the user chose in this version before accepting it, usually nothing. After: the
     * choices recorded with the acceptance, the first ones of each permission from its time on.
     */
    private TermsHistoryDetail accepted(Long id) {
        TermsAcceptance acceptance = acceptances.findById(id)
                .orElseThrow(() -> new TermsHistoryEntryNotFoundException(TermsHistoryEntry.ACCEPTED, id));
        User user = acceptance.getUser();
        TermsVersion version = acceptance.getTermsVersion();
        OffsetDateTime at = acceptance.getAcceptedAt();
        List<OptionalPermissionChoice> made = choices.findByUserAndVersion(user.getId(), version.getId());
        Map<String, Boolean> before = latest(made, c -> c.getChosenAt().isBefore(at));
        Map<String, Boolean> after = new HashMap<>(before);
        Set<String> recorded = new HashSet<>();
        made.stream()
                .filter(c -> !c.getChosenAt().isBefore(at) && recorded.add(c.getPermissionId()))
                .forEach(c -> after.put(c.getPermissionId(), c.isGranted()));
        return detail(TermsHistoryEntry.ACCEPTED, id, version, version.getVersion(), user.getName(), user.getEmail(),
                at, before, after, null);
    }

    /**
     * An {@code updated} entry changed one permission: before is everything up to it but that
     * choice. A refusal changes nothing: before and after are the choices up to it.
     */
    private TermsHistoryDetail fromAudit(String action, Long id, String auditAction) {
        AuditLog entry = logs.findById(id)
                .filter(l -> auditAction.equals(l.getAction()))
                .orElseThrow(() -> new TermsHistoryEntryNotFoundException(action, id));
        JsonNode details = details(entry.getDetails());
        String versionLabel = details.path("version").asText(null);
        TermsVersion version = entry.getEntityId() != null
                ? versions.findById(entry.getEntityId()).orElse(null)
                : versionLabel == null ? null : versions.findByVersionIgnoreCase(versionLabel.strip()).orElse(null);
        if (version != null) {
            versionLabel = version.getVersion();
        }
        User user = entry.getUser();
        OffsetDateTime at = entry.getCreatedAt();
        // The choice is saved before its audit entry, in the same transaction.
        List<OptionalPermissionChoice> upToIt = user == null || version == null
                ? List.of()
                : choices.findByUserAndVersion(user.getId(), version.getId()).stream()
                        .filter(c -> !c.getChosenAt().isAfter(at))
                        .toList();
        String changed = TermsHistoryEntry.UPDATED.equals(action) ? details.path("permission").asText(null) : null;
        OptionalPermissionChoice itsChoice = changed == null
                ? null
                : upToIt.stream().filter(c -> changed.equals(c.getPermissionId())).reduce((a, b) -> b).orElse(null);
        Map<String, Boolean> after = latest(upToIt, c -> true);
        Map<String, Boolean> before = latest(upToIt, c -> c != itsChoice);
        return detail(action, id, version, versionLabel, user == null ? null : user.getName(),
                user == null ? entry.getUserEmail() : user.getEmail(), at, before, after, changed);
    }

    private static TermsHistoryDetail detail(String action, Long id, TermsVersion version, String versionLabel,
            String userName, String userEmail, OffsetDateTime at, Map<String, Boolean> before,
            Map<String, Boolean> after, String changed) {
        List<TermsHistoryDetail.Permission> permissions = version == null
                ? List.of()
                : OptionalPermission.listOf(version.getOptionalPermissions()).stream()
                        .map(p -> new TermsHistoryDetail.Permission(p.id(), p.label(), p.description(),
                                before.get(p.id()), after.get(p.id()), p.id().equals(changed)))
                        .toList();
        return new TermsHistoryDetail(action, id, versionLabel, userName, userEmail, at, permissions);
    }

    /** The last of {@code made} (oldest first) per permission, among those {@code counts} keeps. */
    private static Map<String, Boolean> latest(List<OptionalPermissionChoice> made,
            Predicate<OptionalPermissionChoice> counts) {
        Map<String, Boolean> latest = new HashMap<>();
        made.stream().filter(counts).forEach(c -> latest.put(c.getPermissionId(), c.isGranted()));
        return latest;
    }

    private static JsonNode details(String json) {
        try {
            return json == null ? JSON.createObjectNode() : JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return JSON.createObjectNode();
        }
    }
}
