package com.akpedia.server.dto;

import java.time.Instant;

import org.springframework.http.MediaType;

/**
 * Everything the response needs to know about a stored file except the bytes themselves.
 *
 * <p>Split from the content on purpose: this is what the restrictions and the conditional-request
 * headers are decided from, and a browser that already holds the file only needs this much for the
 * server to answer 304 -- the blob is never read out of the database in that case.
 *
 * @param documentId   document the file belongs to, used to load the bytes when they are needed
 * @param filename     name to offer the browser, always carrying a file extension
 * @param contentType  media type of the stored bytes, already validated
 * @param size         size in bytes recorded for the file
 * @param lastModified when the document last changed, truncated to seconds because that is all
 *                     an HTTP date carries
 * @param etag         strong validator for this version of the file, quoted as the header wants it
 */
public record DocumentFileDescriptor(
        Long documentId,
        String filename,
        MediaType contentType,
        long size,
        Instant lastModified,
        String etag) {
}
