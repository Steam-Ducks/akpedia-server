package com.akpedia.server.service;

/**
 * A document was approved and is waiting to be indexed.
 *
 * <p>Published inside the approval transaction and only acted on once it commits; see
 * {@link ProcessingQueue}.
 *
 * @param documentId the approved document
 */
public record DocumentApprovedEvent(Long documentId) {
}
