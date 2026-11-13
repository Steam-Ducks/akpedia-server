package com.akpedia.server.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A data breach notice an admin sent to the affected users, after ANPD was informed. Never
 * changed once saved: it is the record of what they were told and when.
 */
@Entity
@Table(name = "breach_notices")
public class BreachNotice {

    public static final int MAX_SUBJECT = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sent_by", nullable = false, updatable = false)
    private User sentBy;

    @Column(name = "subject", nullable = false, length = MAX_SUBJECT, updatable = false)
    private String subject;

    /** Plain text, as the admin wrote it and as it was sent. */
    @Column(name = "body", nullable = false, columnDefinition = "text", updatable = false)
    private String body;

    @Column(name = "anpd_informed", nullable = false, updatable = false)
    private boolean anpdInformed;

    @CreationTimestamp
    @Column(name = "sent_at", nullable = false, updatable = false)
    private OffsetDateTime sentAt;

    protected BreachNotice() {
    }

    public BreachNotice(User sentBy, String subject, String body, boolean anpdInformed) {
        this.sentBy = sentBy;
        this.subject = subject;
        this.body = body;
        this.anpdInformed = anpdInformed;
    }

    public Long getId() {
        return id;
    }

    public User getSentBy() {
        return sentBy;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public boolean isAnpdInformed() {
        return anpdInformed;
    }

    public OffsetDateTime getSentAt() {
        return sentAt;
    }
}
