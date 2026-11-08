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
 * One published version of the terms of use. Never changed once saved: acceptances point at it,
 * and a change is a new version.
 */
@Entity
@Table(name = "terms_versions")
public class TermsVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What the admin called it, such as {@code 1.0}; unique in any case. Not an order: see {@code publishedAt}. */
    @Column(name = "version", nullable = false, unique = true, length = 20, updatable = false)
    private String version;

    @Column(name = "title", nullable = false, length = 200, updatable = false)
    private String title;

    /** Plain text, as the admin wrote it; never HTML. */
    @Column(name = "content", nullable = false, columnDefinition = "text", updatable = false)
    private String content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "published_by_id", updatable = false)
    private User publishedBy;

    /** The newest published version is the current one. */
    @CreationTimestamp
    @Column(name = "published_at", nullable = false, updatable = false)
    private OffsetDateTime publishedAt;

    protected TermsVersion() {
    }

    public TermsVersion(String version, String title, String content, User publishedBy) {
        this.version = version;
        this.title = title;
        this.content = content;
        this.publishedBy = publishedBy;
    }

    public Long getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    /** Admin who published it; {@code null} for a version added straight to the database. */
    public User getPublishedBy() {
        return publishedBy;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }
}
