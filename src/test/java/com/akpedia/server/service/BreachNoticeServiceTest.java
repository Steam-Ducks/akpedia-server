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
    /** A short text that keeps every required field, as the law asks. */
    private static final String BODY = "Olá, {{nome_titular}}. Em {{data_descoberta}}, identificamos {{descricao_incidente}} "
            + "({{data_incidente}}). Dados: {{dados_afetados}}. Proteção: {{medidas_protecao}}. "
            + "ANPD em {{data_comunicacao_anpd}}. Medidas: {{medidas_adotadas}}. "
            + "Contato: {{nome_encarregado}}, {{email_encarregado}}.";
    private static final Map<String, String> FIELDS = Map.copyOf(documentExamples());
    /** {@link #BODY} with {@link #FIELDS} in, as it is saved: the name kept as a field. */
    private static final String FILLED = "Olá, {{nome_titular}}. Em 10/11/2026, identificamos Acesso não autorizado a "
            + "uma cópia da base de usuários (08/11 a 09/11/2026). Dados: Nome, e-mail e CPF. Proteção: Servidor com "
            + "acesso restrito. ANPD em 11/11/2026. Medidas: Bloqueio do acesso indevido. Contato: A definir, A definir.";

    /** What one recipient gets. */
    private static String bodyFor(String name) {
        return FILLED.replace("{{nome_titular}}", name);
    }

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
        return new BreachNoticeRequest(SUBJECT, BODY, FIELDS, true, null, true);
    }

    /** Every field the admin fills, as the document's examples; the two optional ones included. */
    private static Map<String, String> documentExamples() {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (BreachNoticeField field : BreachNoticeField.values()) {
            if (!field.automatic()) {
                fields.put(field.key(), field.example());
            }
        }
        return fields;
    }

    @Test
    @DisplayName("the template is the notice document's: its subject, its text, and its fields in order")
    void templateIsTheDocuments() {
        BreachNoticeTemplate template = service.template();

        assertThat(template.subject())
                .isEqualTo("Aviso importante: incidente de segurança envolvendo seus dados no Akpedia");
        assertThat(template.body())
                .startsWith("Olá, {{nome_titular}}.")
                .contains("Comunicamos o incidente à Autoridade Nacional de Proteção de Dados (ANPD) em {{data_comunicacao_anpd}}")
                .contains("{{dados_nao_envolvidos}}", "{{motivo_atraso}}", "{{email_encarregado}}")
                .endsWith("Equipe Akpedia");
        assertThat(template.fields()).extracting(BreachNoticeTemplate.Field::name).containsExactly(
                "nome_titular", "data_descoberta", "data_incidente", "descricao_incidente", "dados_afetados",
                "dados_nao_envolvidos", "medidas_protecao", "data_comunicacao_anpd", "medidas_adotadas",
                "motivo_atraso", "nome_encarregado", "email_encarregado");
        assertThat(template.fields()).filteredOn(field -> !field.required())
                .extracting(BreachNoticeTemplate.Field::name).containsExactly("dados_nao_envolvidos", "motivo_atraso");
        assertThat(template.fields()).filteredOn(BreachNoticeTemplate.Field::automatic)
                .extracting(BreachNoticeTemplate.Field::name).containsExactly("nome_titular");
    }

    @Test
    @DisplayName("every field of the template text is one of the fields listed, and every one listed is in the text")
    void templateTextAndFieldsAgree() {
        BreachNoticeTemplate template = service.template();
        java.util.Set<String> inText = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{\\{(\\w+)}}").matcher(template.body());
        while (matcher.find()) {
            inText.add(matcher.group(1));
        }

        assertThat(inText).containsExactlyInAnyOrderElementsOf(
                template.fields().stream().map(BreachNoticeTemplate.Field::name).toList());
    }

    @Test
    @DisplayName("the document's template, filled: each recipient gets an e-mail with their own name")
    void documentTemplateIsFilledPerRecipient() {
        given(users.findByDeletedAtIsNullAndKeycloakIdIsNotNullOrderById()).willReturn(List.of(ana, bruno));
        given(mail.sendAll(anyCollection())).willReturn(List.of(
                new MailResult.Sent("ana@akaer.com.br", "<1@x>"), new MailResult.Sent("bruno@akaer.com.br", "<2@x>")));
        BreachNoticeTemplate template = service.template();

        service.send(new BreachNoticeRequest(template.subject(), template.body(), documentExamples(), true, null, true),
                ADMIN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutgoingMail>> sent = ArgumentCaptor.forClass(List.class);
        then(mail).should().sendAll(sent.capture());
        OutgoingMail toAna = sent.getValue().get(0);
        assertThat(toAna.subject()).isEqualTo(template.subject());
        assertThat(toAna.body())
                .startsWith("Olá, Usuario 10.")
                .contains("Em 10/11/2026, identificamos Acesso não autorizado a uma cópia da base de usuários. "
                        + "O incidente ocorreu em 08/11 a 09/11/2026.")
                .contains("Nenhuma senha foi exposta")
                .contains("em 11/11/2026, como determina")
                .doesNotContain("{{", "}}");
        assertThat(sent.getValue().get(1).body()).startsWith("Olá, Usuario 11.");

        // What is kept is the filled text, the name left as a field: each row says whose it was.
        ArgumentCaptor<BreachNotice> saved = ArgumentCaptor.forClass(BreachNotice.class);
        then(notices).should().save(saved.capture());
        assertThat(saved.getValue().getBody()).startsWith("Olá, {{nome_titular}}.").contains("10/11/2026");
    }

    @Test
    @DisplayName("an optional field left blank takes its line out, and no blank run is left behind")
    void blankOptionalFieldDropsItsLine() {
        given(users.findByDeletedAtIsNullAndKeycloakIdIsNotNullOrderById()).willReturn(List.of(ana));
        given(mail.sendAll(anyCollection())).willReturn(List.of(new MailResult.Sent("ana@akaer.com.br", "<1@x>")));
        Map<String, String> fields = documentExamples();
        fields.put("dados_nao_envolvidos", "  ");
        fields.remove("motivo_atraso");
        BreachNoticeTemplate template = service.template();

        service.send(new BreachNoticeRequest(template.subject(), template.body(), fields, true, null, true), ADMIN_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutgoingMail>> sent = ArgumentCaptor.forClass(List.class);
        then(mail).should().sendAll(sent.capture());
        String body = sent.getValue().get(0).body();
        assertThat(body).doesNotContain("Nenhuma senha", "Extensão confirmada", "{{").doesNotContain("\n\n\n");
        assertThat(body).contains("são: Nome, e-mail e CPF\n\nQuais são os riscos")
                .contains("medidas: Bloqueio do acesso indevido\n\nO que recomendamos");
    }

    @Test
    @DisplayName("without anpd_informed true, nothing is looked up nor sent: 422")
    void anpdNotInformedIsRefusedFirst() {
        for (Boolean informed : new Boolean[] {null, false}) {
            // Everything else is wrong too: the ANPD confirmation is what is answered.
            BreachNoticeRequest request = new BreachNoticeRequest(null, null, null, null, null, informed);
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
                new OutgoingMail("ana@akaer.com.br", SUBJECT, bodyFor("Usuario 10")),
                new OutgoingMail("bruno@akaer.com.br", SUBJECT, bodyFor("Usuario 11")));

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

        service.send(new BreachNoticeRequest("  " + SUBJECT + "  ", "\n" + BODY + "\n", FIELDS, true, null, true), ADMIN_ID);

        ArgumentCaptor<BreachNotice> saved = ArgumentCaptor.forClass(BreachNotice.class);
        then(notices).should().save(saved.capture());
        assertThat(saved.getValue().getSubject()).isEqualTo(SUBJECT);
        assertThat(saved.getValue().getBody()).isEqualTo(FILLED);
        assertThat(saved.getValue().isAnpdInformed()).isTrue();
        then(users).should().getReferenceById(ADMIN_ID);
    }

    @Test
    @DisplayName("user_ids sends only to those users, each once")
    void sendsToThePickedUsers() {
        given(users.findAllById(any())).willReturn(List.of(bruno));
        given(mail.sendAll(anyCollection())).willReturn(List.of(new MailResult.Sent("bruno@akaer.com.br", "<1@x>")));

        BreachNoticeSendResult result =
                service.send(new BreachNoticeRequest(SUBJECT, BODY, FIELDS, null, List.of(11L, 11L), true), ADMIN_ID);

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
                new BreachNoticeRequest(SUBJECT, BODY, FIELDS, null, List.of(10L, 12L, 13L, 99L), true), ADMIN_ID))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("12, 13, 99");
        then(mail).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("both or neither of all_users and user_ids is refused")
    void exactlyOneWayOfPickingRecipients() {
        assertThatThrownBy(() -> service.send(new BreachNoticeRequest(SUBJECT, BODY, FIELDS, true, List.of(10L), true), ADMIN_ID))
                .isInstanceOf(InvalidBreachNoticeException.class);
        assertThatThrownBy(() -> service.send(new BreachNoticeRequest(SUBJECT, BODY, FIELDS, false, List.of(), true), ADMIN_ID))
                .isInstanceOf(InvalidBreachNoticeException.class);
        then(mail).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("a required field blank is refused, naming every one missing, before anything is looked up")
    void blankRequiredFieldsAreNamed() {
        Map<String, String> fields = documentExamples();
        fields.remove("data_descoberta");
        fields.put("email_encarregado", " ");
        BreachNoticeTemplate template = service.template();

        assertThatThrownBy(() -> service.send(
                new BreachNoticeRequest(template.subject(), template.body(), fields, true, null, true), ADMIN_ID))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("data_descoberta")
                .hasMessageContaining("email_encarregado");
        then(users).shouldHaveNoInteractions();
        then(mail).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("a field the template does not have, the automatic name, or braces in a value are refused")
    void invalidFieldsAreRefused() {
        BreachNoticeTemplate template = service.template();
        List<Map<String, String>> invalid = List.of(
                Map.of("data_descobreta", "10/11/2026"),
                Map.of("nome_titular", "Fulano"),
                Map.of("descricao_incidente", "algo {{nome_titular}}"));
        for (Map<String, String> fields : invalid) {
            Map<String, String> all = documentExamples();
            all.putAll(fields);
            assertThatThrownBy(() -> service.send(
                    new BreachNoticeRequest(template.subject(), template.body(), all, true, null, true), ADMIN_ID))
                    .isInstanceOf(InvalidBreachNoticeException.class);
        }
        // A field the admin typed into the text that the template does not have.
        assertThatThrownBy(() -> service.send(
                new BreachNoticeRequest(SUBJECT, BODY + " Prazo: {{prazo_resposta}}", FIELDS, true, null, true), ADMIN_ID))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("prazo_resposta");
        then(mail).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("a text that dropped a required field is refused, naming them, even with every value given")
    void textMustKeepTheRequiredFields() {
        BreachNoticeTemplate template = service.template();
        String withoutContact = template.body().replace("{{nome_encarregado}}", "o encarregado")
                .replace("{{email_encarregado}}", "dpo@akaer.com.br");
        String withoutName = template.body().replace("Olá, {{nome_titular}}.", "Olá.");

        assertThatThrownBy(() -> service.send(
                new BreachNoticeRequest(template.subject(), withoutContact, documentExamples(), true, null, true), ADMIN_ID))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("nome_encarregado, email_encarregado");
        assertThatThrownBy(() -> service.send(
                new BreachNoticeRequest(template.subject(), withoutName, documentExamples(), true, null, true), ADMIN_ID))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("nome_titular");
        then(users).shouldHaveNoInteractions();
        then(mail).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("the affected data, one per line, starts on a line of its own under the sentence")
    void affectedDataListStartsOnItsOwnLine() {
        given(users.findById(ADMIN_ID)).willReturn(java.util.Optional.of(user(ADMIN_ID, "admin@akaer.com.br")));
        Map<String, String> fields = documentExamples();
        fields.put("dados_afetados", "Nome\nE-mail\nCPF");
        BreachNoticeTemplate template = service.template();

        String body = service.preview(
                new BreachNoticeRequest(template.subject(), template.body(), fields, null, null, null), ADMIN_ID).body();

        assertThat(body).contains("Os dados pessoais seus que podem ter sido expostos são:\nNome\nE-mail\nCPF\n");
    }

    @Test
    @DisplayName("the preview is the e-mail filled, with the admin's name; nothing sent nor saved, ANPD not needed yet")
    void previewSendsAndSavesNothing() {
        given(users.findById(ADMIN_ID)).willReturn(java.util.Optional.of(user(ADMIN_ID, "admin@akaer.com.br")));
        BreachNoticeTemplate template = service.template();

        var preview = service.preview(
                new BreachNoticeRequest(template.subject(), template.body(), documentExamples(), null, null, false), ADMIN_ID);

        assertThat(preview.subject()).isEqualTo(template.subject());
        assertThat(preview.body()).startsWith("Olá, Usuario 1.").contains("em 11/11/2026").doesNotContain("{{");
        then(mail).shouldHaveNoInteractions();
        then(notices).shouldHaveNoInteractions();
        then(recipients).shouldHaveNoInteractions();
        then(audit).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("the preview refuses what sending would: a required field blank")
    void previewChecksLikeSending() {
        Map<String, String> fields = documentExamples();
        fields.remove("medidas_adotadas");
        BreachNoticeTemplate template = service.template();

        assertThatThrownBy(() -> service.preview(
                new BreachNoticeRequest(template.subject(), template.body(), fields, null, null, null), ADMIN_ID))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("medidas_adotadas");
    }

    @Test
    @DisplayName("a blank, multi-line or too long subject, or a blank body, is refused")
    void invalidTextIsRefused() {
        for (BreachNoticeRequest request : List.of(
                new BreachNoticeRequest(" ", BODY, FIELDS, true, null, true),
                new BreachNoticeRequest("linha 1\nBcc: alguem@x.com", BODY, FIELDS, true, null, true),
                new BreachNoticeRequest("a".repeat(201), BODY, FIELDS, true, null, true),
                new BreachNoticeRequest(SUBJECT, "  ", FIELDS, true, null, true))) {
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
        order.verify(recipients).countAlreadySent(eq(SUBJECT), eq(FILLED), eq(List.of(10L)), any());
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
        given(recipients.countAlreadySent(eq(SUBJECT), eq(FILLED), eq(List.of(10L, 11L)), any())).willReturn(1L);

        assertThatThrownBy(() -> service.send(new BreachNoticeRequest(SUBJECT, BODY, FIELDS, null, List.of(10L, 11L), true), ADMIN_ID))
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
