package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.akpedia.server.exception.InvalidBreachNoticeException;

/** The rules of the notice document for filling the {@code {{ }}} fields, one by one. */
class BreachNoticeTextTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

    private static Map<BreachNoticeField, String> values(String... keysAndValues) {
        Map<String, String> given = new HashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            given.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return BreachNoticeText.values(given, TODAY);
    }

    @Test
    @DisplayName("a field takes its value; spaces inside the braces are allowed")
    void fillsFieldsSpacesAllowed() {
        String filled = BreachNoticeText.fill(
                "Em {{data_descoberta}}, {{ descricao_incidente }}.", values("data_descoberta", "2026-11-10",
                        "descricao_incidente", "um acesso indevido"), "body");

        assertThat(filled).isEqualTo("Em 10/11/2026, um acesso indevido.");
    }

    @Test
    @DisplayName("the recipient's name is left as a field, then written in per recipient")
    void recipientNameIsPerRecipient() {
        String filled = BreachNoticeText.fill("Olá, {{ nome_titular }}.", values(), "body");

        assertThat(filled).isEqualTo("Olá, {{nome_titular}}.");
        assertThat(BreachNoticeText.personalize(filled, "Ana Silva")).isEqualTo("Olá, Ana Silva.");
    }

    @Test
    @DisplayName("a list, one per line, starts under the sentence it follows, Windows line breaks made plain")
    void multiLineValueStartsOnItsOwnLine() {
        String filled = BreachNoticeText.fill(
                "Dados: {{dados_afetados}}\nFim", values("dados_afetados", "Nome\r\nE-mail\r\nCPF"), "body");

        assertThat(filled).isEqualTo("Dados:\nNome\nE-mail\nCPF\nFim");
    }

    @Test
    @DisplayName("a one-line value stays on its line, and a list alone on its line does not get a blank one above")
    void singleLineValueAndListAlone() {
        assertThat(BreachNoticeText.fill("Dados: {{dados_afetados}}.", values("dados_afetados", "Nome e CPF"), "body"))
                .isEqualTo("Dados: Nome e CPF.");
        assertThat(BreachNoticeText.fill("Acima\n{{medidas_protecao}}", values("medidas_protecao", "A\nB"), "body"))
                .isEqualTo("Acima\nA\nB");
    }

    @Test
    @DisplayName("a text keeping every required field passes; one that dropped any is refused, naming them all")
    void requiredFieldsMustStayInTheText() {
        StringBuilder all = new StringBuilder();
        for (BreachNoticeField field : BreachNoticeField.values()) {
            if (field.required()) {
                all.append("{{").append(field.key()).append("}} ");
            }
        }
        BreachNoticeText.requireFields("Assunto", all.toString());

        String withoutTwo = all.toString().replace("{{nome_titular}}", "").replace("{{ email_encarregado }}", "")
                .replace("{{email_encarregado}}", "");
        assertThatThrownBy(() -> BreachNoticeText.requireFields("Assunto", withoutTwo))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("nome_titular, email_encarregado");
        // Optional ones may go: the document lets their line be left out.
        assertThat(all.toString()).doesNotContain("motivo_atraso", "dados_nao_envolvidos");
    }

    @Test
    @DisplayName("an optional field blank or absent takes its whole line out, and the blank lines left become one")
    void optionalBlankDropsTheLine() {
        String text = "Primeiro\n\n{{dados_nao_envolvidos}}\n\nSegundo\nMotivo: {{motivo_atraso}}\nTerceiro";

        String filled = BreachNoticeText.fill(text, values("dados_nao_envolvidos", "   "), "body");

        assertThat(filled).isEqualTo("Primeiro\n\nSegundo\nTerceiro");
    }

    @Test
    @DisplayName("an optional field given keeps its line")
    void optionalGivenStays() {
        assertThat(BreachNoticeText.fill("A\n{{motivo_atraso}}\nB", values("motivo_atraso", "Extensão confirmada"), "body"))
                .isEqualTo("A\nExtensão confirmada\nB");
    }

    @Test
    @DisplayName("required fields left blank are refused, every one named")
    void requiredBlankNamesThemAll() {
        assertThatThrownBy(() -> BreachNoticeText.fill(
                "{{data_descoberta}} {{nome_encarregado}} {{email_encarregado}}", values("nome_encarregado", "Ana"), "body"))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("data_descoberta, email_encarregado")
                .hasMessageNotContaining("nome_encarregado");
    }

    @Test
    @DisplayName("a field in the text the template does not have is refused by name")
    void unknownFieldInTheText() {
        assertThatThrownBy(() -> BreachNoticeText.fill("Prazo {{prazo}}", values(), "subject"))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("subject")
                .hasMessageContaining("prazo");
    }

    @Test
    @DisplayName("a value under a name the template does not have, under nome_titular, or with braces is refused")
    void invalidValues() {
        assertThatThrownBy(() -> values("data_descobreta", "x")).isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("data_descobreta");
        assertThatThrownBy(() -> values("nome_titular", "x")).isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("automaticamente");
        assertThatThrownBy(() -> values("medidas_adotadas", "ver {{motivo_atraso}}"))
                .isInstanceOf(InvalidBreachNoticeException.class);
    }

    @Test
    @DisplayName("a text with no fields comes back as it is, and no fields at all is fine")
    void textWithoutFields() {
        assertThat(BreachNoticeText.fill("Sem campos.", BreachNoticeText.values(null, TODAY), "body")).isEqualTo("Sem campos.");
    }

    @Test
    @DisplayName("a date is written as dd/MM/yyyy; a period reads em, or entre ... e ...")
    void datesAreWrittenTheBrazilianWay() {
        assertThat(BreachNoticeText.fill("Em {{data_descoberta}}, ocorreu {{data_incidente}}.",
                values("data_descoberta", "2026-11-10", "data_incidente", "2026-11-08"), "body"))
                .isEqualTo("Em 10/11/2026, ocorreu em 08/11/2026.");
        assertThat(BreachNoticeText.fill("Ocorreu {{data_incidente}}.",
                values("data_incidente", "2026-11-08/2026-11-09"), "body"))
                .isEqualTo("Ocorreu entre 08/11/2026 e 09/11/2026.");
        assertThat(BreachNoticeText.fill("Ocorreu {{data_incidente}}.",
                values("data_incidente", "2026-11-08/2026-11-08"), "body"))
                .isEqualTo("Ocorreu em 08/11/2026.");
    }

    @Test
    @DisplayName("a date that is not a real AAAA-MM-DD date is refused, naming the field")
    void invalidDatesAreRefused() {
        for (String value : new String[] {"abc", "10/11/2026", "2026-02-31", "2026-11-10/", "2026-11-01/2026-11-02/2026-11-03"}) {
            assertThatThrownBy(() -> values("data_incidente", value))
                    .as(value)
                    .isInstanceOf(InvalidBreachNoticeException.class)
                    .hasMessageContaining("data_incidente");
        }
        assertThatThrownBy(() -> values("data_comunicacao_anpd", "ontem"))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("data_comunicacao_anpd");
    }

    @Test
    @DisplayName("no date may be after today, a period's end included; today itself is fine")
    void futureDatesAreRefused() {
        assertThatThrownBy(() -> values("data_descoberta", "2026-11-19"))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("data_descoberta nao pode ser uma data futura");
        assertThatThrownBy(() -> values("data_incidente", "2026-11-17/2026-11-19"))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("data_incidente");
        assertThat(values("data_comunicacao_anpd", "2026-11-18")).containsValue("18/11/2026");
    }

    @Test
    @DisplayName("a period that ends before it starts is refused")
    void periodEndingBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> values("data_incidente", "2026-11-09/2026-11-08"))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("data_incidente termina antes de comecar");
    }

    @Test
    @DisplayName("the incident starts no later than it was found, and ANPD is told no earlier than that")
    void datesAreInOrder() {
        assertThatThrownBy(() -> values("data_incidente", "2026-11-11", "data_descoberta", "2026-11-10"))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("data_incidente nao pode comecar depois de data_descoberta");
        assertThatThrownBy(() -> values("data_descoberta", "2026-11-10", "data_comunicacao_anpd", "2026-11-09"))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("data_comunicacao_anpd nao pode ser antes de data_descoberta");
    }

    @Test
    @DisplayName("an incident still going on when it was found is fine, and the same day for all three too")
    void ongoingIncidentAndSameDayAreFine() {
        assertThat(values("data_incidente", "2026-11-08/2026-11-12", "data_descoberta", "2026-11-10",
                "data_comunicacao_anpd", "2026-11-11")).hasSize(3);
        assertThat(values("data_incidente", "2026-11-10", "data_descoberta", "2026-11-10",
                "data_comunicacao_anpd", "2026-11-10")).hasSize(3);
    }

    @Test
    @DisplayName("a one-line field with a line break is refused; a multi-line one takes it")
    void lineBreaksOnlyInMultiLineFields() {
        assertThatThrownBy(() -> values("descricao_incidente", "linha 1\nlinha 2"))
                .isInstanceOf(InvalidBreachNoticeException.class)
                .hasMessageContaining("descricao_incidente deve ter uma linha so");
        assertThat(values("medidas_adotadas", "A\nB")).containsValue("A\nB");
    }
}
