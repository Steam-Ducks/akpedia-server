-- Data breach notices (LGPD art. 48): the e-mail the admin sends the affected users once ANPD has
-- been informed, and how it went for each recipient. Kept as the record that they were notified,
-- so a notice is never changed nor deleted once sent.

CREATE TABLE breach_notices (
    id            BIGSERIAL PRIMARY KEY,
    sent_by       BIGINT       NOT NULL,
    subject       VARCHAR(200) NOT NULL,
    body          TEXT         NOT NULL,
    -- Always true: the API refuses to send before ANPD is informed. Stored, and checked here too,
    -- because it is what the record has to show.
    anpd_informed BOOLEAN      NOT NULL,
    sent_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_breach_notices_sent_by
        FOREIGN KEY (sent_by) REFERENCES users (id),
    CONSTRAINT ck_breach_notices_anpd_informed CHECK (anpd_informed)
);

-- The admin's list, newest first.
CREATE INDEX ix_breach_notices_sent_at ON breach_notices (sent_at DESC, id DESC);

-- One row per recipient, written PENDING before the e-mails go out and updated as each one does,
-- so a send cut short leaves the ones never sent visible. The e-mail is the address it went to
-- at the time; it is anonymized with the account if the user deletes it.
CREATE TABLE breach_notice_recipients (
    id        BIGSERIAL PRIMARY KEY,
    notice_id BIGINT       NOT NULL,
    user_id   BIGINT       NOT NULL,
    email     VARCHAR(255) NOT NULL,
    status    VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    error     VARCHAR(1000),
    CONSTRAINT fk_breach_notice_recipients_notice_id
        FOREIGN KEY (notice_id) REFERENCES breach_notices (id),
    CONSTRAINT fk_breach_notice_recipients_user_id
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_breach_notice_recipients_notice_user UNIQUE (notice_id, user_id),
    CONSTRAINT ck_breach_notice_recipients_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

-- The notices a user received, for anonymizing them with the account.
CREATE INDEX ix_breach_notice_recipients_user_id ON breach_notice_recipients (user_id);
