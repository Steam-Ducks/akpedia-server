package com.akpedia.server.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corrections the approver makes while approving. Every field is optional: one left out keeps
 * what the author sent, and an approval with no body at all changes nothing but the status.
 *
 * @param name        new title; when given, it cannot be blank
 * @param description new description; a blank one removes it
 * @param categoryId  category to file the document under instead; must exist
 */
public record ApproveDocumentRequest(
        @Size(max = 255) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String name,
        String description,
        Long categoryId) {

    /** No corrections: what an approval without a body means. */
    public static final ApproveDocumentRequest NONE = new ApproveDocumentRequest(null, null, null);
}
