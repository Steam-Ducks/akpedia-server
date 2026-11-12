package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import com.akpedia.server.dto.BreachNoticeRequest;
import com.akpedia.server.dto.BreachNoticeSendResult;
import com.akpedia.server.dto.BreachNoticeTemplate;
import com.akpedia.server.entity.BreachNotice;
import com.akpedia.server.entity.BreachNoticeRecipient;
import com.akpedia.server.entity.Sector;
import com.akpedia.server.entity.User;
import com.akpedia.server.entity.enums.BreachNoticeRecipientStatus;
import com.akpedia.server.exception.AnpdNotInformedException;
import com.akpedia.server.exception.BreachNoticeConflictException;
import com.akpedia.server.exception.InvalidBreachNoticeException;
import com.akpedia.server.repository.BreachNoticeRecipientRepository;
import com.akpedia.server.repository.BreachNoticeRepository;
import com.akpedia.server.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class BreachNoticeServiceTest {

    private static final long ADMIN_ID = 1L;
    private static final long NOTICE_ID = 77L;
    private static final String SUBJECT = "Comunicado sobre incidente de segurança";
    private static final String BODY = "A ANPD foi comunicada. Troque sua senha.";

    @Mock
    private BreachNoticeRepository notices;
    @Mock
    private BreachNoticeRecipientRepository recipients;
    @Mock
    private UserRepository users;
    @Mock
    private MailService mail;
    @Mock
    private AuditService audit;
    @Mock
    private PlatformTransactionManager transactionManager;

    private BreachNoticeService service;

    private final Sector sector = new Sector("Engenharia", null);
    private User ana;
    private User bruno;

    @BeforeEach
    void setUp() {
        service = new BreachNoticeService(notices, recipients, users, mail, audit, transactionManager);
        ana = user(10L, "ana@akaer.com.br");
        bruno = user(11L, "bruno@akaer.com.br");

        lenient().when(notices.save(any(BreachNotice.class))).thenAnswer(call -> {
            BreachNotice notice = call.getArgument(0);
            ReflectionTestUtils.setField(notice, "id", NOTICE_ID);
            return notice;
        });
        lenient().when(recipients.saveAll(anyList())).thenAnswer(call -> new ArrayList<>(call.getArgument(0)));
    }

    private User user(long id, String email) {
        User user = new User("Usuario " + id, email, sector);
        ReflectionTestUtils.setField(user, "id", id);
        user.setKeycloakId(UUID.randomUUID().toString());
        return user;
    }

    private static BreachNoticeRequest toAll() {
        return new BreachNoticeRequest(SUBJECT, BODY, true, null, true);
    }

    @Test
    @DisplayName("the template has a subject and a text with the gaps to fill in")
    void templateHasGaps() {
        BreachNoticeTemplate template = service.template();

        assertThat(template.subject()).isNotBlank().doesNotContain("\n");
        assertThat(template.body()).contains("ANPD").contains(BreachNoticeService.GAP_MARKER);
    }

    @Test
    @DisplayName("without anpd_informed true, nothing is looked up nor sent: 422")
    void anpdNotInformedIsRefusedFirst() {
        for (Boolean informed : new Boolean[] {null, false}) {
            // Everything else is wrong too: the ANPD confirmation is what is answered.
            BreachNoticeRequest request = new BreachNoticeRequest(null, null, null, null, informed);
            assertThatThrownBy(() -> service.send(request, ADMIN_ID)).isInstanceOf(AnpdNotInformedException.class);
        }
        then(users).shouldHaveNoInteractions();
        then(mail).shouldHaveNoInteractions();
        then(notices).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("sends to every user who can be notified and records each one's status")
    void sendsToEveryoneAndRecordsEachStatus() {
        given(users.findByDeletedAtIsNullAndKeycloakIdIsNotNullOrderById()).willReturn(List.of(ana, bruno));
        given(mail.sendAll(anyCollection())).willReturn(List.of(
                new MailResult.Sent("ana@akaer.com.br", "<1@akpedia.local>"),
                new MailResult.Failed("bruno@akaer.com.br", MailResult.Reason.REJECTED, "550 no such user")));

        BreachNoticeSendResult result = service.send(toAll(), ADMIN_ID);

        assertThat(result.id()).isEqualTo(NOTICE_ID);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failures()).containsExactly(new BreachNoticeSendResult.Failure(
                11L, "bruno@akaer.com.br", "REJECTED", "REJECTED: 550 no such user"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutgoingMail>> sent = ArgumentCaptor.forClass(List.class);
        then(mail).should().sendAll(sent.capture());
        assertThat(sent.getValue()).containsExactly(
                new OutgoingMail("ana@akaer.com.br", SUBJECT, BODY),
                new OutgoingMail("bruno@akaer.com.br", SUBJECT, BODY));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BreachNoticeRecipient>> saved = ArgumentCaptor.forClass(List.class);
        then(recipients).should(org.mockito.Mockito.times(2)).saveAll(saved.capture());
        List<BreachNoticeRecipient> rows = saved.getAllValues().get(1);
        assertThat(rows).extracting(BreachNoticeRecipient::getStatus)
                .containsExactly(BreachNoticeRecipientStatus.SENT, BreachNoticeRecipientStatus.FAILED);
        assertThat(rows.get(1).getError()).isEqualTo("REJECTED: 550 no such user");

        then(audit).should().record(eq(ADMIN_ID), eq(AuditService.BREACH_NOTICE_SENT), eq(AuditService.BREACH_NOTICE),
                eq(NOTICE_ID), eq(Map.of("total", 2, "sent", 1, "failed", 1)));
    }

    @Test
    @DisplayName("the notice is saved as ANPD informed, by the admin, with the text stripped")
    void noticeIsSavedAsSent() {
        given(users.findByDeletedAtIsNullAndKeycloakIdIsNotNullOrderById()).willReturn(List.of(ana));
        given(mail.sendAll(anyCollection())).willReturn(List.of(new MailResult.Sent("ana@akaer.com.br", "<1@x>")));

        service.send(new BreachNoticeRequest("  " + SUBJECT + "  ", "\n" + BODY + "\n", true, null, true), ADMIN_ID);

        ArgumentCaptor<BreachNotice> saved = ArgumentCaptor.forClass(BreachNotice.class);
        then(notices).should().save(saved.capture());
        assertThat(saved.getValue().getSubject()).isEqualTo(SUBJECT);
        assertThat(saved.getValue().getBody()).isEqualTo(BODY);
        assertThat(saved.getValue().isAnpdInformed()).isTrue();
        then(users).should().getReferenceById(ADMIN_ID);
    }

    @Test
    @DisplayName("user_ids sends only to those users, each once")
    void sendsToThePickedUsers() {
        given(users.findAllById(any())).willReturn(List.of(bruno));
        given(mail.sendAll(anyCollection())).willReturn(List.of(new MailResult.Sent("bruno@akaer.com.br", "<1@x>")));

        BreachNoticeSendResult result =
                service.send(new BreachNoticeRequest(SUBJECT, BODY, null, List.of(11L, 11L), true), ADMIN_ID);

        assertThat(result.total()).isEqualTo(1);
        then(users).should(never()).findByDeletedAtIsNullAndKeycloakIdIsNotNullOrderById();
    }

    @Test
    @DisplayName("a picked user who does not exist, was deleted or has no Keycloak account is refused by id")
    void unknownPickedUsersAreNamed() {
        User deleted = user(12L, "removido-12@removido.invalid");
        deleted.anonymize(java.time.OffsetDateTime.now());
        User unlinked = user(13L, "semconta@akaer.com.br");
        unlinked.setKeycloakId(null);
        given(users.findAllById(any())).willReturn(List.of(ana, deleted, unlinked));

        assertThatThrownBy(() -> service.send(
                new BreachNoticeRequest(SUBJECT, BODY, null, List.of(10L, 12L, 13L, 99L), true), ADMIN_ID))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("12, 13, 99");
        then(mail).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("both or neither of all_users and user_ids is refused")
    void exactlyOneWayOfPickingRecipients() {
        assertThatThrownBy(() -> service.send(new BreachNoticeRequest(SUBJECT, BODY, true, List.of(10L), true), ADMIN_ID))
                .isInstanceOf(InvalidBreachNoticeException.class);
        assertThatThrownBy(() -> service.send(new BreachNoticeRequest(SUBJECT, BODY, false, List.of(), true), ADMIN_ID))
                .isInstanceOf(InvalidBreachNoticeException.class);
        then(mail).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("a text with a template gap left, a blank or multi-line subject, or a blank body is refused")
    void invalidTextIsRefused() {
        String gap = "Dados afetados: [PREENCHER: a natureza dos dados]";
        for (BreachNoticeRequest request : List.of(
                new BreachNoticeRequest(SUBJECT, gap, true, null, true),
                new BreachNoticeRequest("[PREENCHER: assunto]", BODY, true, null, true),
                new BreachNoticeRequest(" ", BODY, true, null, true),
                new BreachNoticeRequest("linha 1\nBcc: alguem@x.com", BODY, true, null, true),
                new BreachNoticeRequest("a".repeat(201), BODY, true, null, true),
                new BreachNoticeRequest(SUBJECT, "  ", true, null, true))) {
            assertThatThrownBy(() -> service.send(request, ADMIN_ID)).isInstanceOf(InvalidBreachNoticeException.class);
        }
        then(mail).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("the send lock is taken before checking for repeats, and before saving the notice")
    void lockComesFirst() {
        given(users.findByDeletedAtIsNullAndKeycloakIdIsNotNullOrderById()).willReturn(List.of(ana));
        given(mail.sendAll(anyCollection())).willReturn(List.of(new MailResult.Sent("ana@akaer.com.br", "<1@x>")));

        service.send(toAll(), ADMIN_ID);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(recipients, notices, mail);
        order.verify(recipients).lockSending(BreachNoticeService.SEND_LOCK_KEY);
        order.verify(recipients).existsPendingSince(any());
        order.verify(recipients).countAlreadySent(eq(SUBJECT), eq(BODY), eq(List.of(10L)), any());
        order.verify(notices).save(any());
        order.verify(mail).sendAll(anyCollection());
    }

    @Test
    @DisplayName("while another notice is being sent, 409 in progress: nothing saved nor sent")
    void anotherSendInProgressIsRefused() {
        given(users.findByDeletedAtIsNullAndKeycloakIdIsNotNullOrderById()).willReturn(List.of(ana));
        given(recipients.existsPendingSince(any())).willReturn(true);

        assertThatThrownBy(() -> service.send(toAll(), ADMIN_ID))
                .isInstanceOf(BreachNoticeConflictException.class)
                .extracting("code").isEqualTo(BreachNoticeConflictException.IN_PROGRESS);
        then(notices).should(never()).save(any());
        then(mail).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("only the last 30 minutes count as in progress, so a send cut short long ago blocks nothing")
    void inProgressLooksAtTheLastHalfHour() {
        given(users.findByDeletedAtIsNullAndKeycloakIdIsNotNullOrderById()).willReturn(List.of(ana));
        given(mail.sendAll(anyCollection())).willReturn(List.of(new MailResult.Sent("ana@akaer.com.br", "<1@x>")));
        java.time.OffsetDateTime before = java.time.OffsetDateTime.now();

        service.send(toAll(), ADMIN_ID);

        ArgumentCaptor<java.time.OffsetDateTime> since = ArgumentCaptor.forClass(java.time.OffsetDateTime.class);
        then(recipients).should().existsPendingSince(since.capture());
        assertThat(since.getValue()).isBetween(
                before.minusMinutes(BreachNoticeService.REPEAT_WINDOW_MINUTES).minusSeconds(5),
                java.time.OffsetDateTime.now().minusMinutes(BreachNoticeService.REPEAT_WINDOW_MINUTES));
    }

    @Test
    @DisplayName("the same notice already sent to some of these users is 409 duplicate: nothing saved nor sent")
    void sameNoticeAlreadySentIsRefused() {
        given(users.findAllById(any())).willReturn(List.of(ana, bruno));
        given(recipients.countAlreadySent(eq(SUBJECT), eq(BODY), eq(List.of(10L, 11L)), any())).willReturn(1L);

        assertThatThrownBy(() -> service.send(new BreachNoticeRequest(SUBJECT, BODY, null, List.of(10L, 11L), true), ADMIN_ID))
                .isInstanceOf(BreachNoticeConflictException.class)
                .hasMessageContaining("1 destes usuarios")
                .extracting("code").isEqualTo(BreachNoticeConflictException.DUPLICATE);
        then(notices).should(never()).save(any());
        then(mail).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("with no user to notify, nothing is saved")
    void noRecipientsIsRefused() {
        given(users.findByDeletedAtIsNullAndKeycloakIdIsNotNullOrderById()).willReturn(List.of());

        assertThatThrownBy(() -> service.send(toAll(), ADMIN_ID)).isInstanceOf(InvalidBreachNoticeException.class);
        then(notices).should(never()).save(any());
        then(audit).should(never()).record(anyLong(), anyString(), anyString(), anyLong(), anyMap());
    }
}
