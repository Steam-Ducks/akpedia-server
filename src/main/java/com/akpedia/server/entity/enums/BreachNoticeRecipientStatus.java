package com.akpedia.server.entity.enums;

/** How the breach notice went for one recipient. */
public enum BreachNoticeRecipientStatus {
    /** Not sent yet: the notice is being sent, or the send was cut short before this one. */
    PENDING,
    /** The SMTP server accepted the e-mail. */
    SENT,
    /** The e-mail did not go out; the row's error says why. */
    FAILED
}
