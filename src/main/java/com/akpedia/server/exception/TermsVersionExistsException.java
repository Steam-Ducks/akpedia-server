package com.akpedia.server.exception;

/** A version of the terms with that name, in any case, was published before. */
public class TermsVersionExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TermsVersionExistsException(String version) {
        super("Já existe a versão %s. Recarregue a página e publique de novo.".formatted(version));
    }
}
