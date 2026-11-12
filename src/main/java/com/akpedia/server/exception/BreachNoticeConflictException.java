package com.akpedia.server.exception;

/**
 * The breach notice was not sent because it would repeat or overlap another: one is still being
 * sent, or this same notice already reached some of these users moments ago.
 *
 * <p>Usually a second click, or the screen trying again after a slow answer. Sending the notice
 * twice is what this prevents: people told twice about the same incident take it as two incidents.
 */
public class BreachNoticeConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Another notice is being sent right now. */
    public static final String IN_PROGRESS = "breach_notice_in_progress";

    /** This notice was already sent to some of these users. */
    public static final String DUPLICATE = "breach_notice_duplicate";

    private final String code;

    private BreachNoticeConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static BreachNoticeConflictException inProgress() {
        return new BreachNoticeConflictException(IN_PROGRESS,
                "Outro aviso de vazamento esta sendo enviado agora. Aguarde ele terminar e confira a lista de avisos.");
    }

    public static BreachNoticeConflictException duplicate(long alreadySent, int minutes) {
        return new BreachNoticeConflictException(DUPLICATE, ("Este aviso ja foi enviado a %d destes usuarios nos ultimos %d minutos. "
                + "Para reenviar so a quem falhou, use user_ids com esses usuarios.").formatted(alreadySent, minutes));
    }

    /** {@link #IN_PROGRESS} or {@link #DUPLICATE}. */
    public String getCode() {
        return code;
    }
}
