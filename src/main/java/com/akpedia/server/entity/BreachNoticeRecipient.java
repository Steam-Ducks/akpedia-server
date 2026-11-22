package com.akpedia.server.entity;

import com.akpedia.server.entity.enums.BreachNoticeRecipientStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One user a breach notice was sent to, at which address, and how it went. */
@Entity
@Table(name = "breach_notice_recipients")
public class BreachNoticeRecipient {

    public static final int MAX_ERROR = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notice_id", nullable = false, updatable = false)
    private BreachNotice notice;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** The address it was sent to, which the user's row may no longer have. */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private BreachNoticeRecipientStatus status = BreachNoticeRecipientStatus.PENDING;

    @Column(name = "error", length = MAX_ERROR)
    private String error;

    protected BreachNoticeRecipient() {
    }

    public BreachNoticeRecipient(BreachNotice notice, User user, String email) {
        this.notice = notice;
        this.user = user;
        this.email = email;
    }

    public void markSent() {
        this.status = BreachNoticeRecipientStatus.SENT;
        this.error = null;
    }

    /** Failed, with why, cut to what the column takes. */
    public void markFailed(String error) {
        this.status = BreachNoticeRecipientStatus.FAILED;
        this.error = error == null || error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR);
    }

    public Long getId() {
        return id;
    }

    public BreachNotice getNotice() {
        return notice;
    }

    public User getUser() {
        return user;
    }

    public String getEmail() {
        return email;
    }

    public BreachNoticeRecipientStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }
}
