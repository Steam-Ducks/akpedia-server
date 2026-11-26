package com.akpedia.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A user allowing, or not, an optional permission of a version of the terms. Append-only: the
 * latest one per user, version and permission counts.
 */
@Entity
@Table(name = "optional_permission_choices")
public class OptionalPermissionChoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "terms_version_id", nullable = false, updatable = false)
    private TermsVersion termsVersion;

    @Column(name = "permission_id", nullable = false, length = 100, updatable = false)
    private String permissionId;

    @Column(name = "granted", nullable = false, updatable = false)
    private boolean granted;

    @CreationTimestamp
    @Column(name = "chosen_at", nullable = false, updatable = false)
    private OffsetDateTime chosenAt;

    protected OptionalPermissionChoice() {
    }

    public OptionalPermissionChoice(User user, TermsVersion termsVersion, String permissionId, boolean granted) {
        this.user = user;
        this.termsVersion = termsVersion;
        this.permissionId = permissionId;
        this.granted = granted;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public TermsVersion getTermsVersion() {
        return termsVersion;
    }

    public String getPermissionId() {
        return permissionId;
    }

    public boolean isGranted() {
        return granted;
    }

    public OffsetDateTime getChosenAt() {
        return chosenAt;
    }
}
