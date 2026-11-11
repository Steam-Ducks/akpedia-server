package com.akpedia.server.security;

/**
 * Who is opening a document, as far as deciding whether they may.
 *
 * @param userId   the user's row
 * @param reviewer whether they hold a role that reviews documents ({@code APPROVER} or
 *                 {@code ADMIN}), which is what lets them open a document awaiting approval
 *                 outside their sector's categories
 */
public record Viewer(Long userId, boolean reviewer) {
}
