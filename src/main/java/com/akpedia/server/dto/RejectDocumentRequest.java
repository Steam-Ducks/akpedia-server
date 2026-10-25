package com.akpedia.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Why the approver is returning the document.
 *
 * @param comment the justification the author sees beside the returned document; required
 */
public record RejectDocumentRequest(@NotBlank String comment) {
}
