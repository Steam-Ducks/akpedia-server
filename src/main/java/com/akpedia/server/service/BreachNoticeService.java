package com.akpedia.server.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.akpedia.server.dto.BreachNoticeDetail;
import com.akpedia.server.dto.BreachNoticeRequest;
import com.akpedia.server.dto.BreachNoticeSendResult;
import com.akpedia.server.dto.BreachNoticeSummary;
import com.akpedia.server.dto.BreachNoticeTemplate;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.entity.BreachNotice;
import com.akpedia.server.entity.BreachNoticeRecipient;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.BreachNoticeRecipientStatus;
import com.akpedia.server.exception.AnpdNotInformedException;
import com.akpedia.server.exception.BreachNoticeConflictException;
import com.akpedia.server.exception.BreachNoticeNotFoundException;
import com.akpedia.server.exception.InvalidBreachNoticeException;
import com.akpedia.server.exception.InvalidPageRequestException;
import com.akpedia.server.repository.BreachNoticeRecipientRepository;
import com.akpedia.server.repository.BreachNoticeRepository;
import com.akpedia.server.repository.UserRepository;
import com.akpedia.server.service.MailResult.Failed;

/**
 * The data breach notice (LGPD art. 48): the admin sends the affected users an e-mail saying what
 * happened, that ANPD has been informed, and what is being done.
 *
 * <p>It only goes out once the admin confirms ANPD was informed, and never with a gap of the
 * template ({@value #GAP_MARKER}) left unfilled. The recipients are users with a row linked to a
 * Keycloak account and not deleted -- every one of them, or the ones the admin picked.
 *
 * <p>Sending is done in three steps, so the record never says more than what happened: the notice
 * and one {@code PENDING} row per recipient are saved first; then the e-mails go out through
 * {@link MailService}, outside any transaction, as SMTP can take a while; then each row gets its
 * {@code SENT} or {@code FAILED}, and the audit trail a {@code BREACH_NOTICE_SENT} with the counts.
 * A send cut short midway leaves its rows {@code PENDING}, visible on the notice.
 *
 * <p>A notice is never sent twice by accident -- a second click, or the screen trying again after a
 * slow answer: while one is going out, or once it reached these users, the same send is refused.
 */
@Service
public class BreachNoticeService {

    /** What marks a gap of the template the admin has to fill in. */
    static final String GAP_MARKER = "[PREENCHER";

    static final int MAX_BODY = 50_000;

    /** How far back a send in progress, or the same notice already sent, refuses a new send. */
    static final int REPEAT_WINDOW_MINUTES = 30;

    /** The advisory lock every send takes; any constant no other code locks on. */
    static final long SEND_LOCK_KEY = 0x6272656163680001L;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private static final String TEMPLATE_SUBJECT = "breach-notice/subject.txt";
    private static final String TEMPLATE_BODY = "breach-notice/body.txt";

    private final BreachNoticeRepository notices;
    private final BreachNoticeRecipientRepository recipients;
    private final UserRepository users;
    private final MailService mail;
    private final AuditService audit;
    private final TransactionTemplate transaction;
    private final BreachNoticeTemplate template;

    public BreachNoticeService(
            BreachNoticeRepository notices,
            BreachNoticeRecipientRepository recipients,
            UserRepository users,
            MailService mail,
            AuditService audit,
            PlatformTransactionManager transactionManager) {
        this.notices = notices;
        this.recipients = recipients;
        this.users = users;
        this.mail = mail;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        // Read once, at startup: a template missing from the build fails the boot, not the incident.
        this.template = new BreachNoticeTemplate(read(TEMPLATE_SUBJECT).strip(), read(TEMPLATE_BODY).strip());
    }

    /** The suggested subject and text, for the admin to complete. */
    public BreachNoticeTemplate template() {
        return template;
    }

    /**
     * Sends the notice to its recipients and records how it went for each.
     *
     * @param request the notice
     * @param adminId the admin sending it
     * @throws AnpdNotInformedException     if {@code anpd_informed} is not {@code true}; checked first
     * @throws InvalidBreachNoticeException if the subject, text or recipients are missing or invalid
     * @throws BreachNoticeConflictException if another notice is being sent, or this one already
     *         reached some of these users in the last {@value #REPEAT_WINDOW_MINUTES} minutes; nothing
     *         is sent nor saved then
     */
    public BreachNoticeSendResult send(BreachNoticeRequest request, Long adminId) {
        if (!Boolean.TRUE.equals(request.anpdInformed())) {
            throw new AnpdNotInformedException();
        }
        String subject = validSubject(request.subject());
        String body = validBody(request.body());
        List<User> to = recipientsOf(request);

        // 1. The notice and its recipients, all PENDING -- unless it would repeat or overlap another.
        List<BreachNoticeRecipient> rows = transaction.execute(status -> {
            refuseRepeats(subject, body, to);
            BreachNotice notice = notices.save(new BreachNotice(users.getReferenceById(adminId), subject, body, true));
            return recipients.saveAll(to.stream()
                    .map(user -> new BreachNoticeRecipient(notice, user, user.getEmail()))
                    .toList());
        });
        Long noticeId = rows.get(0).getNotice().getId();

        // 2. The e-mails, one result per recipient in the same order. MailService never throws.
        List<MailResult> results = mail.sendAll(rows.stream()
                .map(row -> new OutgoingMail(row.getEmail(), subject, body))
                .toList());

        // 3. How it went.
        List<BreachNoticeSendResult.Failure> failures = new ArrayList<>();
        transaction.executeWithoutResult(status -> {
            for (int i = 0; i < rows.size(); i++) {
                BreachNoticeRecipient row = rows.get(i);
                if (results.get(i) instanceof Failed failed) {
                    String error = "%s: %s".formatted(failed.reason(), failed.detail());
                    row.markFailed(error);
                    failures.add(new BreachNoticeSendResult.Failure(
                            row.getUser().getId(), row.getEmail(), failed.reason().name(), row.getError()));
                } else {
                    row.markSent();
                }
            }
            recipients.saveAll(rows);
            audit.record(adminId, AuditService.BREACH_NOTICE_SENT, AuditService.BREACH_NOTICE, noticeId,
                    AuditService.details("total", rows.size(), "sent", rows.size() - failures.size(),
                            "failed", failures.size()));
        });

        return new BreachNoticeSendResult(
                noticeId, rows.size(), rows.size() - failures.size(), failures.size(), List.copyOf(failures));
    }

    /**
     * Refuses a second send while one is going out, or of a notice that already reached these users.
     *
     * <p>Under a lock held until the notice's rows are committed, so two requests at once cannot both
     * pass: the second waits, then finds the first one's {@code PENDING} rows. A notice whose send was
     * cut short leaves rows {@code PENDING} for good, so only the last {@value #REPEAT_WINDOW_MINUTES}
     * minutes count -- past that, it is not in progress any more and does not block the next one.
     *
     * <p>Sending the same text again to the users it failed for is allowed: only the ones it reached
     * count as a repeat.
     */
    private void refuseRepeats(String subject, String body, List<User> to) {
        recipients.lockSending(SEND_LOCK_KEY);
        OffsetDateTime since = OffsetDateTime.now().minusMinutes(REPEAT_WINDOW_MINUTES);
        if (recipients.existsPendingSince(since)) {
            throw BreachNoticeConflictException.inProgress();
        }
        long alreadySent = recipients.countAlreadySent(subject, body, to.stream().map(User::getId).toList(), since);
        if (alreadySent > 0) {
            throw BreachNoticeConflictException.duplicate(alreadySent, REPEAT_WINDOW_MINUTES);
        }
    }

    /**
     * One page of the notices sent, newest first.
     *
     * @throws InvalidPageRequestException if the page is negative or the size out of range
     */
    @Transactional(readOnly = true)
    public PageResponse<BreachNoticeSummary> list(Integer requestedPage, Integer requestedSize) {
        int page = requestedPage == null ? 0 : requestedPage;
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPageRequestException(MAX_PAGE_SIZE);
        }
        var found = notices.findPage(
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("sentAt"), Sort.Order.desc("id"))));
        Map<Long, Map<BreachNoticeRecipientStatus, Long>> counts = countsOf(
                found.getContent().stream().map(BreachNotice::getId).toList());
        return PageResponse.from(found, notice -> {
            Map<BreachNoticeRecipientStatus, Long> count = counts.getOrDefault(notice.getId(), Map.of());
            return new BreachNoticeSummary(
                    notice.getId(),
                    notice.getSubject(),
                    notice.getSentBy().getName(),
                    notice.isAnpdInformed(),
                    notice.getSentAt(),
                    total(count),
                    count.getOrDefault(BreachNoticeRecipientStatus.SENT, 0L),
                    count.getOrDefault(BreachNoticeRecipientStatus.FAILED, 0L),
                    count.getOrDefault(BreachNoticeRecipientStatus.PENDING, 0L));
        });
    }

    /**
     * The notice as sent, with each recipient.
     *
     * @throws BreachNoticeNotFoundException if there is none with that id
     */
    @Transactional(readOnly = true)
    public BreachNoticeDetail get(Long id) {
        BreachNotice notice = notices.findWithSenderById(id).orElseThrow(() -> new BreachNoticeNotFoundException(id));
        List<BreachNoticeDetail.Recipient> list = recipients.findByNoticeIdOrderById(id).stream()
                .map(row -> new BreachNoticeDetail.Recipient(
                        row.getUser().getId(), row.getUser().getName(), row.getEmail(), row.getStatus(), row.getError()))
                .toList();
        Map<BreachNoticeRecipientStatus, Long> count = new EnumMap<>(BreachNoticeRecipientStatus.class);
        list.forEach(recipient -> count.merge(recipient.status(), 1L, Long::sum));
        return new BreachNoticeDetail(
                notice.getId(),
                notice.getSubject(),
                notice.getBody(),
                notice.getSentBy().getName(),
                notice.isAnpdInformed(),
                notice.getSentAt(),
                list.size(),
                count.getOrDefault(BreachNoticeRecipientStatus.SENT, 0L),
                count.getOrDefault(BreachNoticeRecipientStatus.FAILED, 0L),
                count.getOrDefault(BreachNoticeRecipientStatus.PENDING, 0L),
                list);
    }

    private static String validSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new InvalidBreachNoticeException("subject e obrigatorio.");
        }
        String stripped = subject.strip();
        // A line break in a header would let the text add headers of its own.
        if (stripped.indexOf('\r') >= 0 || stripped.indexOf('\n') >= 0) {
            throw new InvalidBreachNoticeException("subject deve ter uma linha so.");
        }
        if (stripped.length() > BreachNotice.MAX_SUBJECT) {
            throw new InvalidBreachNoticeException(
                    "subject deve ter no maximo %d caracteres.".formatted(BreachNotice.MAX_SUBJECT));
        }
        if (stripped.contains(GAP_MARKER)) {
            throw new InvalidBreachNoticeException("subject ainda tem um trecho [PREENCHER: ...] do modelo.");
        }
        return stripped;
    }

    private static String validBody(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidBreachNoticeException("body e obrigatorio.");
        }
        String stripped = body.strip();
        if (stripped.length() > MAX_BODY) {
            throw new InvalidBreachNoticeException("body deve ter no maximo %d caracteres.".formatted(MAX_BODY));
        }
        if (stripped.contains(GAP_MARKER)) {
            throw new InvalidBreachNoticeException(
                    "body ainda tem trechos [PREENCHER: ...] do modelo: complete-os antes de enviar.");
        }
        return stripped;
    }

    /** Every user who can be notified, or the ones picked; each one once, in id order. */
    private List<User> recipientsOf(BreachNoticeRequest request) {
        boolean all = Boolean.TRUE.equals(request.allUsers());
        boolean picked = request.userIds() != null && !request.userIds().isEmpty();
        if (all == picked) {
            throw new InvalidBreachNoticeException("Informe all_users: true ou user_ids, um dos dois.");
        }

        List<User> to;
        if (all) {
            to = users.findByDeletedAtIsNullAndKeycloakIdIsNotNullOrderById();
        } else {
            if (request.userIds().stream().anyMatch(Objects::isNull)) {
                throw new InvalidBreachNoticeException("user_ids nao pode ter valores nulos.");
            }
            Set<Long> ids = new LinkedHashSet<>(request.userIds());
            Map<Long, User> found = new HashMap<>();
            users.findAllById(ids).stream().filter(BreachNoticeService::canBeNotified)
                    .forEach(user -> found.put(user.getId(), user));
            List<Long> unknown = ids.stream().filter(id -> !found.containsKey(id)).toList();
            if (!unknown.isEmpty()) {
                throw new InvalidBreachNoticeException("Usuarios nao encontrados, removidos ou sem conta no Keycloak: %s."
                        .formatted(unknown.stream().map(String::valueOf).collect(Collectors.joining(", "))));
            }
            to = found.values().stream().sorted((a, b) -> a.getId().compareTo(b.getId())).toList();
        }
        if (to.isEmpty()) {
            throw new InvalidBreachNoticeException("Nao ha usuarios para notificar.");
        }
        return to;
    }

    private static boolean canBeNotified(User user) {
        return user.getDeletedAt() == null && user.getKeycloakId() != null;
    }

    private Map<Long, Map<BreachNoticeRecipientStatus, Long>> countsOf(List<Long> noticeIds) {
        Map<Long, Map<BreachNoticeRecipientStatus, Long>> counts = new HashMap<>();
        if (noticeIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : recipients.countByStatus(noticeIds)) {
            counts.computeIfAbsent((Long) row[0], id -> new EnumMap<>(BreachNoticeRecipientStatus.class))
                    .put((BreachNoticeRecipientStatus) row[1], ((Number) row[2]).longValue());
        }
        return counts;
    }

    private static long total(Map<BreachNoticeRecipientStatus, Long> count) {
        return count.values().stream().mapToLong(Long::longValue).sum();
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The breach notice template " + path + " is missing from the build.", e);
        }
    }
}
