package com.akpedia.server.entity;

import java.time.OffsetDateTime;

import org.hibernate.annotations.CreationTimestamp;

import com.akpedia.server.entity.enums.ChangeRequestStatus;
import com.akpedia.server.entity.enums.ProfileField;

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

/**
 * A user asking an admin to change their e-mail or CPF, which they cannot change themself.
 * Holds personal data (the new value, the reason), so it is deleted with the account.
 */
@Entity
@Table(name = "profile_change_requests")
public class ProfileChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "field", nullable = false, length = 10)
    private ProfileField field;

    @Column(name = "new_value", nullable = false, length = 255)
    private String newValue;

    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ChangeRequestStatus status = ChangeRequestStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_id")
    private User decidedBy;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ProfileChangeRequest() {
    }

    public ProfileChangeRequest(User user, ProfileField field, String newValue, String reason) {
        this.user = user;
        this.field = field;
        this.newValue = newValue;
        this.reason = reason;
    }

    /** Closes the request: approved or rejected, by this admin, with an optional note. */
    public void decide(ChangeRequestStatus decision, User admin, String note, OffsetDateTime when) {
        this.status = decision;
        this.decidedBy = admin;
        this.decisionNote = note;
        this.decidedAt = when;
    }

    public boolean isPending() {
        return status == ChangeRequestStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public ProfileField getField() {
        return field;
    }

    public String getNewValue() {
        return newValue;
    }

    public String getReason() {
        return reason;
    }

    public ChangeRequestStatus getStatus() {
        return status;
    }

    public User getDecidedBy() {
        return decidedBy;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
