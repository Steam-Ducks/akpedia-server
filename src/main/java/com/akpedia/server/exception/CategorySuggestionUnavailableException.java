package com.akpedia.server.exception;

/**
 * Thrown when the category could not be suggested because akpedia-ml did not answer.
 *
 * <p>Separate from {@link EmbeddingUnavailableException} so the upload screen can tell this
 * outage apart from any other: this is the one case where the right move is to let the user pick
 * the category by hand and carry on, rather than to show a failure and stop.
 */
public class CategorySuggestionUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CategorySuggestionUnavailableException(Throwable cause) {
        super("Nao foi possivel sugerir a categoria: o servico de classificacao esta indisponivel. "
                + "Selecione a categoria manualmente para continuar o envio.", cause);
    }

}
