package com.akpedia.server.dto;

/**
 * The admin rejecting a change request.
 *
 * @param note shown to the user, optional, at most 500 characters
 */
public record RejectChangeRequest(String note) {
}
