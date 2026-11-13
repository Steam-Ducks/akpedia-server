package com.akpedia.server.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * The {@code {{ }}} fields of the data breach notice template, as the notice document defines them
 * (section 4, "Campos do template"): which are required, what each one is, and an example.
 *
 * <p>{@link #NOME_TITULAR} is the only one the admin does not fill: it is each recipient's name,
 * written in at send time. An optional field left blank takes its line out of the e-mail.
 */
public enum BreachNoticeField {

    NOME_TITULAR("nome_titular", true, true,
            "Nome do funcionário que recebe o aviso", "Ana Silva"),
    DATA_DESCOBERTA("data_descoberta", true, false,
            "Data em que a empresa tomou conhecimento do incidente", "10/11/2026"),
    DATA_INCIDENTE("data_incidente", true, false,
            "Data ou período estimado do incidente", "08/11 a 09/11/2026"),
    DESCRICAO_INCIDENTE("descricao_incidente", true, false,
            "Descrição objetiva do ocorrido", "Acesso não autorizado a uma cópia da base de usuários"),
    DADOS_AFETADOS("dados_afetados", true, false,
            "Dados efetivamente afetados, um por linha", "Nome, e-mail e CPF"),
    DADOS_NAO_ENVOLVIDOS("dados_nao_envolvidos", false, false,
            "Dados confirmadamente não afetados", "Nenhuma senha foi exposta"),
    MEDIDAS_PROTECAO("medidas_protecao", true, false,
            "Medidas de segurança existentes antes do incidente", "Servidor com acesso restrito"),
    DATA_COMUNICACAO_ANPD("data_comunicacao_anpd", true, false,
            "Data da comunicação à ANPD", "11/11/2026"),
    MEDIDAS_ADOTADAS("medidas_adotadas", true, false,
            "Medidas tomadas após o incidente", "Bloqueio do acesso indevido"),
    MOTIVO_ATRASO("motivo_atraso", false, false,
            "Justificativa caso o aviso ultrapasse o prazo", "Extensão confirmada posteriormente"),
    NOME_ENCARREGADO("nome_encarregado", true, false,
            "Nome do encarregado de dados (DPO)", "A definir"),
    EMAIL_ENCARREGADO("email_encarregado", true, false,
            "E-mail de contato do encarregado", "A definir");

    private final String key;
    private final boolean required;
    private final boolean automatic;
    private final String description;
    private final String example;

    BreachNoticeField(String key, boolean required, boolean automatic, String description, String example) {
        this.key = key;
        this.required = required;
        this.automatic = automatic;
        this.description = description;
        this.example = example;
    }

    /** The name between the braces, such as {@code data_descoberta}. */
    public String key() {
        return key;
    }

    /** Whether the notice can only go out with it filled. */
    public boolean required() {
        return required;
    }

    /** Whether the server fills it, per recipient, instead of the admin. */
    public boolean automatic() {
        return automatic;
    }

    public String description() {
        return description;
    }

    public String example() {
        return example;
    }

    /** The field with this name, if the template has one. */
    public static Optional<BreachNoticeField> byKey(String key) {
        return Arrays.stream(values()).filter(field -> field.key.equals(key)).findFirst();
    }
}
