package com.akpedia.server.service;

/**
 * A failed document was put back to {@code PROCESSING} and is waiting to be indexed again.
 *
 * <p>Published inside the reprocess transaction and only acted on once it commits; see
 * {@link ProcessingQueue}.
 *
 * @param documentId the document to reprocess
 */
public record DocumentReprocessRequestedEvent(Long documentId) {
}
