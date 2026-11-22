package com.akpedia.server.service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.akpedia.server.dto.AuditEntry;
import com.akpedia.server.dto.AuditKind;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.entity.AuditLog;
import com.akpedia.server.exception.InvalidPageRequestException;
import com.akpedia.server.repository.AuditLogRepository;
import com.akpedia.server.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The audit trail: records what users did, both the named events (a document approved, the terms
 * accepted...) and every {@code /api} request, and lists it for the audit screen.
 *
 * <p>Who did it comes from the request's access token: their Keycloak id and email are copied
 * into the entry, and their {@code users} row is linked when there is one. A row is never created
 * here: a request refused before reaching the app should not create a user.
 *
 * <p>For an account that was deleted, only the Keycloak id is kept: no email, IP or user agent.
 * That covers the deletion's own request and event, and any request still made with the deleted
 * user's token.
 */
@Service
public class AuditService {

    public static final String TERMS_ACCEPTED = "TERMS_ACCEPTED";
    public static final String TERMS_DECLINED = "TERMS_DECLINED";
    public static final String TERMS_PUBLISHED = "TERMS_PUBLISHED";
    public static final String DOCUMENT_UPLOADED = "DOCUMENT_UPLOADED";
    public static final String DOCUMENT_APPROVED = "DOCUMENT_APPROVED";
    public static final String DOCUMENT_REJECTED = "DOCUMENT_REJECTED";
    public static final String USER_ROLES_CHANGED = "USER_ROLES_CHANGED";
    public static final String USER_ENABLED = "USER_ENABLED";
    public static final String USER_DISABLED = "USER_DISABLED";
    public static final String USER_CREATED = "USER_CREATED";
    public static final String ACCOUNT_DELETED = "ACCOUNT_DELETED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String PROFILE_UPDATED = "PROFILE_UPDATED";
    public static final String PROFILE_CHANGE_REQUESTED = "PROFILE_CHANGE_REQUESTED";
    public static final String PROFILE_CHANGE_CANCELLED = "PROFILE_CHANGE_CANCELLED";
    public static final String PROFILE_CHANGE_APPROVED = "PROFILE_CHANGE_APPROVED";
    public static final String PROFILE_CHANGE_REJECTED = "PROFILE_CHANGE_REJECTED";
    public static final String SECTOR_CATEGORIES_CHANGED = "SECTOR_CATEGORIES_CHANGED";
    public static final String BREACH_NOTICE_SENT = "BREACH_NOTICE_SENT";

    public static final String TERMS_VERSION = "TERMS_VERSION";
    public static final String DOCUMENT = "DOCUMENT";
    public static final String USER = "USER";
    public static final String PROFILE_CHANGE_REQUEST = "PROFILE_CHANGE_REQUEST";
    public static final String SECTOR = "SECTOR";
    public static final String BREACH_NOTICE = "BREACH_NOTICE";

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    private static final int MAX_PATH = 500;
    private static final int MAX_USER_AGENT = 500;
    private static final int MAX_IP = 45;
    private static final int MAX_QUERY = 2000;

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** Details are plain maps of strings, numbers and lists: no configuration of the app's mapper needed. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuditLogRepository logs;
    private final UserRepository users;
    private final AccountDeletionLog deletions;

    public AuditService(AuditLogRepository logs, UserRepository users, AccountDeletionLog deletions) {
        this.logs = logs;
        this.users = users;
        this.deletions = deletions;
    }

    /**
     * Details for an entry, from alternating keys and values, leaving out the {@code null} values
     * so an optional field is simply absent.
     */
    public static Map<String, Object> details(Object... keysAndValues) {
        Map<String, Object> details = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                details.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
        }
        return details;
    }

    /**
     * Records a named event, in the caller's transaction: if the action rolls back, so does its
     * event. The method and path are the current request's.
     *
     * @param userId     the acting user's row, when the caller already has it; otherwise looked up
     *                   from the token
     * @param action     one of the event names above
     * @param entityType what the event is about, if anything
     * @param entityId   its id
     * @param details    what else is worth keeping; see {@link #details}
     */
    @Transactional
    public void record(Long userId, String action, String entityType, Long entityId, Map<String, ?> details) {
        AuditLog entry = new AuditLog(null, action, entityType, entityId);
        describeUser(entry, userId);
        describeRequest(entry, currentRequest());
        forgetDeletedUser(entry);
        entry.setDetails(toJson(details));
        logs.save(entry);
    }

    /**
     * Records one finished {@code /api} request. Its body is never recorded, only its query string.
     *
     * @param status     the status the response went out with
     * @param durationMs how long the request took to answer
     */
    @Transactional
    public void recordRequest(HttpServletRequest request, int status, long durationMs) {
        AuditLog entry = new AuditLog(null, AuditLog.HTTP_REQUEST, null, null);
        describeUser(entry, null);
        describeRequest(entry, request);
        forgetDeletedUser(entry);
        entry.setStatusCode(status);
        entry.setDurationMs((int) Math.min(durationMs, Integer.MAX_VALUE));
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            entry.setDetails(toJson(Map.of("query", truncate(query, MAX_QUERY))));
        }
        logs.save(entry);
    }

    /**
     * One page of the audit trail, newest first.
     *
     * @param kind  events (the default), requests, or both
     * @param user  part of the user's email
     * @throws InvalidPageRequestException if the page is negative or the size out of range
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> search(AuditKind kind, String action, String user,
            OffsetDateTime from, OffsetDateTime to, Integer requestedPage, Integer requestedSize) {
        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPageRequestException(MAX_PAGE_SIZE);
        }
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.from(
                logs.findAll(AuditLogRepository.matching(kind == null ? AuditKind.EVENT : kind, action, user, from, to),
                        pageable),
                entry -> AuditEntry.of(entry, parse(entry.getDetails()), deletions.isDeleted(entry.getUserKeycloakId())));
    }

    /** The named events recorded so far, sorted, for the audit screen's filter. */
    @Transactional(readOnly = true)
    public List<String> eventActions() {
        // Sorted here: the database's collation would order the underscores its own way.
        return logs.findDistinctActionsExcept(AuditLog.HTTP_REQUEST).stream().sorted().toList();
    }

    private void describeUser(AuditLog entry, Long userId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            entry.setUserKeycloakId(jwt.getSubject());
            entry.setUserEmail(truncate(jwt.getClaimAsString("email"), 255));
            if (userId == null && jwt.getSubject() != null) {
                userId = users.findIdByKeycloakId(jwt.getSubject()).orElse(null);
            }
        }
        if (userId != null) {
            entry.setUser(users.getReferenceById(userId));
        }
    }

    /**
     * Who a deleted account was, from where and with what, is not kept. The entry points at the
     * account's anonymized row instead, so it shows the same pseudonym as the account's others.
     */
    private void forgetDeletedUser(AuditLog entry) {
        if (deletions.isDeleted(entry.getUserKeycloakId())) {
            entry.setUserEmail(null);
            entry.setIpAddress(null);
            entry.setUserAgent(null);
            if (entry.getUser() == null) {
                deletions.rowOf(entry.getUserKeycloakId())
                        .flatMap(users::findById)
                        .filter(row -> row.getDeletedAt() != null)
                        .ifPresent(entry::setUser);
            }
        }
    }

    private static void describeRequest(AuditLog entry, HttpServletRequest request) {
        if (request == null) {
            return;
        }
        entry.setIpAddress(truncate(clientIp(request), MAX_IP));
        entry.setUserAgent(truncate(request.getHeader("User-Agent"), MAX_USER_AGENT));
        entry.setHttpMethod(truncate(request.getMethod(), 10));
        entry.setPath(truncate(request.getRequestURI().substring(request.getContextPath().length()), MAX_PATH));
    }

    /** The first address of {@code X-Forwarded-For}, set by a proxy in front, or the peer's. */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }

    private static String toJson(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details cannot be written as JSON.", e);
        }
    }

    private static JsonNode parse(String details) {
        if (details == null) {
            return null;
        }
        try {
            return JSON.readTree(details);
        } catch (JsonProcessingException e) {
            log.warn("Audit details that are not JSON: {}", details, e);
            return JSON.getNodeFactory().textNode(details);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
