package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param entries      how many deletions the log holds, all replayed
 * @param deletedAgain how many of them had come back (a backup restored) and were deleted again
 */
public record AccountDeletionReplayResponse(int entries, @JsonProperty("deleted_again") int deletedAgain) {
}
