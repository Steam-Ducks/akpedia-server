package com.akpedia.server.exception;

/** There is no sector with that id. */
public class SectorNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SectorNotFoundException(Long sectorId) {
        super("Setor %d não encontrado.".formatted(sectorId));
    }
}
