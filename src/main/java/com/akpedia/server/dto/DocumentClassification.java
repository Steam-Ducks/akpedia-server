package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.Map;

/**
 * What akpedia-ml answers to {@code POST /api/v1/documents/analyze}: suggestions to pre-fill the
 * upload form, read from the document itself.
 *
 * <p>The category arrives as a name rather than an id because akpedia-ml does not read this
 * server's database; turning that name into a {@code categories} row is this server's job.
 *
 * <p>{@code category} is {@code null} when akpedia-ml has no category centroids deployed. That is
 * a legitimate "no suggestion", not a contract violation: {@code confidence} is then {@code null}
 * and {@code scores} empty, while the title and description still come through.
 *
 * @param title       suggested title, at most 255 characters
 * @param description first substantial paragraph, at most 500 characters; {@code null} when none
 * @param category    name of the suggested category, as the classifier labels it, or {@code null}
 * @param confidence  probability of {@code category}, from 0 to 1, or {@code null}
 * @param scores      probability of every category, summing to 1; empty without a category
 * @param pages       number of pages of the PDF that was analyzed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentClassification(
        String title,
        String description,
        String category,
        BigDecimal confidence,
        Map<String, BigDecimal> scores,
        Integer pages) {
}
