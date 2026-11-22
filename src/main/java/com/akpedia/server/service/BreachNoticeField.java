package com.akpedia.server.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * The {@code {{ }}} fields of the data breach notice template, as the notice document defines them
 * (section 4, "Campos do template"): which are required, what each one is, and an example.
 *
 * <p>{@link #NOME_TITULAR} is the only one the admin does not fill: it is each recipient's name,
 * written in at send time. An optional field left blank takes its line out of the e-mail. Each field's
 * {@link Type} says how its value is sent and checked; its example is a value as it is sent.
 */
public enum BreachNoticeField {

    NOME_TITULAR("nome_titular", true, true, Type.TEXT,
            "Nome do funcionário que recebe o aviso", "Ana Silva"),
    DATA_DESCOBERTA("data_descoberta", true, false, Type.DATE,
            "Data em que a empresa tomou conhecimento do incidente", "2026-11-10"),
    DATA_INCIDENTE("data_incidente", true, false, Type.DATE_RANGE,
            "Data ou período estimado do incidente", "2026-11-08/2026-11-09"),
    DESCRICAO_INCIDENTE("descricao_incidente", true, false, Type.TEXT,
            "Descrição objetiva do ocorrido", "Acesso não autorizado a uma cópia da base de usuários"),
    DADOS_AFETADOS("dados_afetados", true, false, Type.MULTILINE,
            "Dados efetivamente afetados, um por linha", "Nome\nE-mail\nCPF"),
    DADOS_NAO_ENVOLVIDOS("dados_nao_envolvidos", false, false, Type.MULTILINE,
            "Dados confirmadamente não afetados", "Nenhuma senha foi exposta"),
    MEDIDAS_PROTECAO("medidas_protecao", true, false, Type.MULTILINE,
            "Medidas de segurança existentes antes do incidente", "Servidor com acesso restrito"),
    DATA_COMUNICACAO_ANPD("data_comunicacao_anpd", true, false, Type.DATE,
            "Data da comunicação à ANPD", "2026-11-11"),
    MEDIDAS_ADOTADAS("medidas_adotadas", true, false, Type.MULTILINE,
            "Medidas tomadas após o incidente", "Bloqueio do acesso indevido"),
    MOTIVO_ATRASO("motivo_atraso", false, false, Type.MULTILINE,
            "Justificativa caso o aviso ultrapasse o prazo", "Extensão confirmada posteriormente"),
    NOME_ENCARREGADO("nome_encarregado", true, false, Type.TEXT,
            "Nome do encarregado de dados (DPO)", "A definir"),
    EMAIL_ENCARREGADO("email_encarregado", true, false, Type.TEXT,
            "E-mail de contato do encarregado", "A definir");

    /** What a field's value is, which says how the admin types it and how it is checked. */
    public enum Type {
        /** One line of text. */
        TEXT,
        /** Text that may span lines, such as a list, one item per line. */
        MULTILINE,
        /** A date, sent as {@code 2026-11-10} and written as {@code 10/11/2026}. */
        DATE,
        /**
         * A date, or a start and an end ({@code 2026-11-08/2026-11-09}), written as
         * {@code em 08/11/2026} or {@code entre 08/11/2026 e 09/11/2026}.
         */
        DATE_RANGE
    }

    private final String key;
    private final boolean required;
    private final boolean automatic;
    private final Type type;
    private final String description;
    private final String example;

    BreachNoticeField(String key, boolean required, boolean automatic, Type type, String description, String example) {
        this.key = key;
        this.required = required;
        this.automatic = automatic;
        this.type = type;
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

    public Type type() {
        return type;
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
