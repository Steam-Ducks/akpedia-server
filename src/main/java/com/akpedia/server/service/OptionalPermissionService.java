package com.akpedia.server.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akpedia.server.dto.AdminOptionalPermission;
import com.akpedia.server.dto.AdminOptionalPermissionsResponse;
import com.akpedia.server.dto.OptionalPermission;
import com.akpedia.server.dto.OptionalPermissionChoiceResponse;
import com.akpedia.server.dto.OptionalPermissionDraft;
import com.akpedia.server.dto.UserOptionalPermission;
import com.akpedia.server.dto.UserOptionalPermissionsResponse;
import com.akpedia.server.entity.OptionalPermissionChoice;
import com.akpedia.server.entity.TermsVersion;
import com.akpedia.server.exception.InvalidOptionalPermissionException;
import com.akpedia.server.exception.OptionalPermissionNotFoundException;
import com.akpedia.server.exception.OptionalPermissionsChangedException;
import com.akpedia.server.repository.OptionalPermissionChoiceRepository;
import com.akpedia.server.repository.TermsVersionRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * Optional permissions (e.g. "E-mails sobre novidades") are part of the terms of use: each version
 * lists its own, so adding, changing or removing one publishes a new version, which everyone accepts
 * again. Unlike the terms, they are optional: saying no never limits what the user can do.
 *
 * <p>A choice only counts for the version of the terms it was made in, so a new version starts with
 * everything off (opt-in) until the user chooses on the terms screen, or later on their account page.
 */
@Service
public class OptionalPermissionService {

    private final TermsVersionRepository versions;
    private final OptionalPermissionChoiceRepository choices;
    private final UserRepository users;
    private final AuditService audit;

    public OptionalPermissionService(TermsVersionRepository versions, OptionalPermissionChoiceRepository choices,
            UserRepository users, AuditService audit) {
        this.versions = versions;
        this.choices = choices;
        this.users = users;
        this.audit = audit;
    }

    /** The optional permissions of the current terms, with this user's choice for each. */
    @Transactional(readOnly = true)
    public UserOptionalPermissionsResponse forUser(Long userId) {
        List<TermsVersion> history = versions.findAllByOrderByPublishedAtDescIdDesc();
        if (history.isEmpty()) {
            return new UserOptionalPermissionsResponse(null, null, List.of());
        }
        TermsVersion current = history.get(0);
        List<OptionalPermission> previous = history.size() > 1
                ? OptionalPermission.listOf(history.get(1).getOptionalPermissions())
                : null;
        Map<String, OptionalPermissionChoice> latest = new LinkedHashMap<>();
        choices.findByUserAndVersion(userId, current.getId()).forEach(c -> latest.put(c.getPermissionId(), c));
        List<UserOptionalPermission> permissions = OptionalPermission.listOf(current.getOptionalPermissions())
                .stream()
                .map(p -> {
                    OptionalPermissionChoice choice = latest.get(p.id());
                    return new UserOptionalPermission(p.id(), p.label(), p.description(),
                            choice != null && choice.isGranted(), choice != null,
                            choice == null ? null : choice.getChosenAt(), change(p, previous));
                })
                .toList();
        return new UserOptionalPermissionsResponse(current.getVersion(), current.getPublishedAt(), permissions);
    }

    /**
     * Turns one optional permission of the current terms on or off.
     *
     * @throws OptionalPermissionsChangedException if {@code version} is not the current terms
     * @throws OptionalPermissionNotFoundException if the current terms have no such permission
     */
    @Transactional
    public OptionalPermissionChoiceResponse choose(Long userId, String permissionId, boolean granted, String version) {
        TermsVersion current = versions.findFirstByOrderByPublishedAtDescIdDesc()
                .orElseThrow(() -> new OptionalPermissionsChangedException(null));
        if (!current.getVersion().equalsIgnoreCase(version.strip())) {
            throw new OptionalPermissionsChangedException(current.getVersion());
        }
        if (OptionalPermission.listOf(current.getOptionalPermissions()).stream()
                .noneMatch(p -> p.id().equals(permissionId))) {
            throw new OptionalPermissionNotFoundException(permissionId);
        }
        OptionalPermissionChoice choice = record(userId, current, permissionId, granted);
        audit.record(userId, AuditService.OPTIONAL_PERMISSION_CHOSEN, AuditService.TERMS_VERSION, current.getId(),
                AuditService.details("version", current.getVersion(), "permission", permissionId, "granted", granted));
        return new OptionalPermissionChoiceResponse(permissionId, current.getVersion(), granted, choice.getChosenAt());
    }

    /**
     * Records the user's choices made while accepting {@code terms}: one per optional permission of
     * that version, those left out (or unknown) as not allowed.
     */
    @Transactional
    public void recordAcceptance(Long userId, TermsVersion terms, Map<String, Boolean> granted) {
        for (OptionalPermission p : OptionalPermission.listOf(terms.getOptionalPermissions())) {
            record(userId, terms, p.id(), Boolean.TRUE.equals(granted.get(p.id())));
        }
    }

    /** Turns off everything the user allowed, in every version, e.g. when they delete their account. */
    @Transactional
    public void revokeAll(Long userId) {
        Map<String, OptionalPermissionChoice> latest = new LinkedHashMap<>();
        for (OptionalPermissionChoice choice : choices.findByUser(userId)) {
            latest.put(choice.getTermsVersion().getId() + "@" + choice.getPermissionId(), choice);
        }
        for (OptionalPermissionChoice choice : latest.values()) {
            if (choice.isGranted()) {
                record(userId, choice.getTermsVersion(), choice.getPermissionId(), false);
            }
        }
    }

    /** The optional permissions of the current terms, with who allowed each one. */
    @Transactional(readOnly = true)
    public AdminOptionalPermissionsResponse forAdmin() {
        TermsVersion current = versions.findFirstByOrderByPublishedAtDescIdDesc().orElse(null);
        if (current == null) {
            return new AdminOptionalPermissionsResponse(null, null, null, List.of());
        }
        Map<String, OptionalPermissionChoice> latest = new LinkedHashMap<>();
        choices.findByVersion(current.getId())
                .forEach(c -> latest.put(c.getUser().getId() + "@" + c.getPermissionId(), c));
        Map<String, List<AdminOptionalPermission.GrantedUser>> granted = new LinkedHashMap<>();
        for (OptionalPermissionChoice choice : latest.values()) {
            if (choice.isGranted() && choice.getUser().getDeletedAt() == null) {
                granted.computeIfAbsent(choice.getPermissionId(), k -> new ArrayList<>())
                        .add(new AdminOptionalPermission.GrantedUser(choice.getUser().getId(),
                                choice.getUser().getName(), choice.getUser().getEmail(), choice.getChosenAt()));
            }
        }
        List<AdminOptionalPermission> permissions = OptionalPermission.listOf(current.getOptionalPermissions())
                .stream()
                .map(p -> new AdminOptionalPermission(p.id(), p.label(), p.description(),
                        granted.getOrDefault(p.id(), List.of())))
                .toList();
        String publisher = current.getPublishedBy() == null ? null : current.getPublishedBy().getName();
        return new AdminOptionalPermissionsResponse(current.getVersion(), current.getPublishedAt(), publisher,
                permissions);
    }

    /**
     * The optional permissions of a new version of the terms, from the admin's drafts. A draft with an
     * id keeps that permission of the current version; one without gets a new id, never one any
     * version used before.
     *
     * @throws InvalidOptionalPermissionException if an id is not in the current version, or repeated
     */
    @Transactional(readOnly = true)
    public List<OptionalPermission> forNewVersion(List<OptionalPermissionDraft> drafts) {
        Set<String> current = new HashSet<>();
        versions.findFirstByOrderByPublishedAtDescIdDesc().ifPresent(v ->
                OptionalPermission.listOf(v.getOptionalPermissions()).forEach(p -> current.add(p.id())));
        Set<String> used = new HashSet<>();
        versions.findAll().forEach(v -> OptionalPermission.listOf(v.getOptionalPermissions())
                .forEach(p -> used.add(p.id())));
        List<OptionalPermission> permissions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (OptionalPermissionDraft draft : drafts) {
            String id = draft.id() == null || draft.id().isBlank() ? null : draft.id().strip();
            if (id != null && !current.contains(id)) {
                throw new InvalidOptionalPermissionException(
                        "A permissão opcional %s não está na versão atual dos termos.".formatted(id));
            }
            if (id == null) {
                String base = slug(draft.label());
                id = base;
                for (int n = 2; used.contains(id) || seen.contains(id); n++) {
                    id = base + "-" + n;
                }
            }
            if (!seen.add(id)) {
                throw new InvalidOptionalPermissionException(
                        "A permissão opcional %s aparece duas vezes.".formatted(id));
            }
            permissions.add(new OptionalPermission(id, draft.label().strip(), draft.description().strip()));
        }
        return permissions;
    }

    private OptionalPermissionChoice record(Long userId, TermsVersion terms, String permissionId, boolean granted) {
        return choices.saveAndFlush(new OptionalPermissionChoice(
                users.getReferenceById(userId), terms, permissionId, granted));
    }

    private static String change(OptionalPermission permission, List<OptionalPermission> previous) {
        if (previous == null) {
            return null;
        }
        OptionalPermission before = previous.stream()
                .filter(p -> p.id().equals(permission.id()))
                .findFirst().orElse(null);
        if (before == null) {
            return "new";
        }
        return Objects.equals(before.label(), permission.label())
                && Objects.equals(before.description(), permission.description()) ? null : "changed";
    }

    static String slug(String label) {
        String slug = Normalizer.normalize(label, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        if (slug.length() > 80) {
            slug = slug.substring(0, 80);
        }
        return slug.isEmpty() ? "permissao" : slug;
    }
}
