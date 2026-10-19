package com.akpedia.server.exception;

/**
 * Thrown when akpedia-ml suggests a category name that is not in the {@code categories} catalog.
 *
 * <p>Treated as a downstream contract violation rather than as "no suggestion": the classifier
 * and the catalog are supposed to hold the same set of categories, so a name that does not
 * resolve means one of the two drifted and somebody has to reconcile them.
 */
public class UnknownSuggestedCategoryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnknownSuggestedCategoryException(String categoryName) {
        super("O servico de classificacao sugeriu a categoria '%s', que nao existe no catalogo."
                .formatted(categoryName));
    }

}
