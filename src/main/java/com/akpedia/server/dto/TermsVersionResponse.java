package com.akpedia.server.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.akpedia.server.entity.TermsVersion;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A published version of the terms of use.
 *
 * @param version             what the admin called this version, such as {@code 1.0}
 * @param content             plain text, paragraphs separated by blank lines; never HTML
 * @param publishedBy         name of the admin who published it, or {@code null}
 * @param optionalPermissions what users may agree to without being required to
 */
public record TermsVersionResponse(
        String version,
        String title,
        String content,
        @JsonProperty("published_at") OffsetDateTime publishedAt,
        @JsonProperty("published_by") String publishedBy,
        @JsonProperty("optional_permissions") List<OptionalPermission> optionalPermissions) {

    public TermsVersionResponse(String version, String title, String content, OffsetDateTime publishedAt,
            String publishedBy) {
        this(version, title, content, publishedAt, publishedBy, List.of());
    }

    public static TermsVersionResponse of(TermsVersion terms) {
        String publisher = terms.getPublishedBy() != null ? terms.getPublishedBy().getName() : null;
        return new TermsVersionResponse(terms.getVersion(), terms.getTitle(), terms.getContent(),
                terms.getPublishedAt(), publisher, OptionalPermission.listOf(terms.getOptionalPermissions()));
    }
}
