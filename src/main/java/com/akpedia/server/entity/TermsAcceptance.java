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

/** A user accepting a version of the terms of use. Append-only: the record of who accepted what, and when. */
@Entity
@Table(name = "terms_acceptances")
public class TermsAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "terms_version_id", nullable = false, updatable = false)
    private TermsVersion termsVersion;

    @CreationTimestamp
    @Column(name = "accepted_at", nullable = false, updatable = false)
    private OffsetDateTime acceptedAt;

    protected TermsAcceptance() {
    }

    public TermsAcceptance(User user, TermsVersion termsVersion) {
        this.user = user;
        this.termsVersion = termsVersion;
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

    public OffsetDateTime getAcceptedAt() {
        return acceptedAt;
    }
}
