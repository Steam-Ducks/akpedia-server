package com.akpedia.server.exception;

/** A breach notice sent without confirming that ANPD was informed first. */
public class AnpdNotInformedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AnpdNotInformedException() {
        super("O aviso só pode ser enviado depois que a ANPD for comunicada: confirme com anpd_informed: true.");
    }
}
