package com.akpedia.server.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/**
 * What akpedia-ml answers for a classification: which category it recognised, and how sure it is.
 *
 * <p>The category arrives as a name rather than an id because akpedia-ml does not read this
 * server's database; turning that name into a {@code categories} row is this server's job.
 *
 * @param filename   name of the file that was classified, echoed back by the service
 * @param category   name of the suggested category, as the classifier labels it
 * @param confidence how sure the classifier is, from 0 to 1
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentClassification(
        String filename,
        String category,
        BigDecimal confidence) {
}
