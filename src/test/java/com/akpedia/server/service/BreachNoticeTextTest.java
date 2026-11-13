package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.akpedia.server.exception.InvalidBreachNoticeException;

/** The rules of the notice document for filling the {@code {{ }}} fields, one by one. */
class BreachNoticeTextTest {

    private static Map<BreachNoticeField, String> values(String... keysAndValues) {
        Map<String, String> given = new HashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            given.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return BreachNoticeText.values(given);
    }

    @Test
    @DisplayName("a field takes its value; spaces inside the braces are allowed")
    void fillsFieldsSpacesAllowed() {
        String filled = BreachNoticeText.fill(
                "Em {{data_descoberta}} e {{ data_incidente }}.", values("data_descoberta", "10/11", "data_incidente", "08/11"), "body");

        assertThat(filled).isEqualTo("Em 10/11 e 08/11.");
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
        assertThat(BreachNoticeText.fill("Sem campos.", BreachNoticeText.values(null), "body")).isEqualTo("Sem campos.");
    }
}
