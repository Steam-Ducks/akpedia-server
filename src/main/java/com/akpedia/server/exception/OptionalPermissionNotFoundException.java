package com.akpedia.server.exception;

/** There is no such optional permission (enabled, for a user's choice) in the current version. */
public class OptionalPermissionNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OptionalPermissionNotFoundException(String id) {
        super("Permissão opcional não encontrada: %s.".formatted(id));
    }
}
