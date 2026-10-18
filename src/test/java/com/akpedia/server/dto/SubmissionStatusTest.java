package com.akpedia.server.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;

/**
 * The whole table of what an author is shown, one row per stored combination. Spelled out rather
 * than derived, so that changing what any combination reads as has to change a line here too.
 */
class SubmissionStatusTest {

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @DisplayName("every stored combination reads as the documented submission status")
    @CsvSource(nullValues = "NONE", value = {
        "PENDING_APPROVAL, PENDING,    UNDER_REVIEW",
        "PENDING_APPROVAL, PROCESSING, UNDER_REVIEW",
        "PENDING_APPROVAL, COMPLETED,  UNDER_REVIEW",
        "PENDING_APPROVAL, FAILED,     UNDER_REVIEW",
        "APPROVED,         PENDING,    PROCESSING",
        "APPROVED,         PROCESSING, PROCESSING",
        "APPROVED,         COMPLETED,  PUBLISHED",
        "APPROVED,         FAILED,     INDEXING_FAILED",
        "REJECTED,         PENDING,    RETURNED",
        "REJECTED,         PROCESSING, RETURNED",
        "REJECTED,         COMPLETED,  RETURNED",
        "REJECTED,         FAILED,     RETURNED",
        "DRAFT,            PENDING,    NONE",
        "DRAFT,            COMPLETED,  NONE",
        "ARCHIVED,         PENDING,    NONE",
        "ARCHIVED,         COMPLETED,  NONE",
    })
    void mapsEveryCombination(DocumentStatus status, ProcessingStatus processing, SubmissionStatus expected) {
        assertThat(SubmissionStatus.of(status, processing)).isEqualTo(expected);
    }
}
