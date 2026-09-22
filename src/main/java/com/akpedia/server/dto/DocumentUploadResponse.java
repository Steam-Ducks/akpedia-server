package com.akpedia.server.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;

/**
 * What the caller gets back after a document is stored: metadata only, the PDF bytes
 * themselves never round-trip through this response.
 *
 * @param id               generated identifier
 * @param name             document name
 * @param description      optional description
 * @param mimeType         always {@code application/pdf}: the file was converted before saving
 * @param fileSize         size in bytes of the stored PDF
 * @param categoryId       category the document was filed under
 * @param creatorId        user who uploaded it
 * @param status           approval status, starts as {@code DRAFT}
 * @param processingStatus chunk/embedding processing status: {@code COMPLETED} once
 *                         akpedia-ml embedded the document, {@code FAILED} if it could not
 * @param processingError  why embedding failed, present only when {@code processingStatus} is {@code FAILED}
 * @param createdAt        when the document was stored
 */
public record DocumentUploadResponse(
        Long id,
        String name,
        String description,
        @JsonProperty("mime_type") String mimeType,
        @JsonProperty("file_size") Long fileSize,
        @JsonProperty("category_id") Long categoryId,
        @JsonProperty("creator_id") Long creatorId,
        DocumentStatus status,
        @JsonProperty("processing_status") ProcessingStatus processingStatus,
        @JsonProperty("processing_error") String processingError,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    public static DocumentUploadResponse from(Document document) {
        return new DocumentUploadResponse(
                document.getId(),
                document.getName(),
                document.getDescription(),
                document.getMimeType(),
                document.getFileSize(),
                document.getCategory().getId(),
                document.getCreator().getId(),
                document.getStatus(),
                document.getProcessingStatus(),
                document.getProcessingError(),
                document.getCreatedAt());
    }

}
