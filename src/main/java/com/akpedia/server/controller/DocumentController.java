package com.akpedia.server.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.DocumentUploadResponse;
import com.akpedia.server.entity.Document;
import com.akpedia.server.service.DocumentUploadService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Uploads documents into the database.
 *
 * <p>Whatever format arrives, the file that gets stored is always a PDF: this is what lets
 * every other document-processing feature -- embeddings included -- assume a single format
 * instead of handling one converter per file type. The upload also triggers the embedding
 * of that PDF through akpedia-ml, so a stored document is a searchable one.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Documents", description = "Document upload: convert to PDF, store, and index with akpedia-ml.")
public class DocumentController {

    private static final String ERROR_SCHEMA = "application/json";

    private final DocumentUploadService uploadService;

    public DocumentController(DocumentUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @Operation(
            summary = "Upload a document, convert it to PDF, and index it with akpedia-ml",
            description = """
                    Accepts any format Gotenberg can convert through LibreOffice (docx, xlsx, pptx, odt, rtf,
                    images, plain text...). Uploaded PDFs are stored directly without reconversion.
                    The stored binary is always the resulting PDF; the original format is not retained.

                    After storage, the PDF is sent to akpedia-ml to generate embeddings, which are stored in
                    `embeddings`. A failure at this stage does not undo the upload: the stored document keeps
                    `processing_status: FAILED` and the reason in `processing_error` for a later retry.
                    Only PDF conversion is required for a 201 response.""")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
                description = "Document converted and stored; processing_status indicates whether akpedia-ml indexing succeeded."),
        @ApiResponse(responseCode = "400", description = "Invalid request (empty file, missing parameter, or invalid parameter).",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "The specified category or user does not exist.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "File exceeds the accepted limit.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Gotenberg received the file but rejected the conversion.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "O Gotenberg respondeu algo inesperado.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "Gotenberg is unavailable.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUploadResponse> upload(
            @RequestPart("file")
            @Schema(type = "string", format = "binary", description = "File to upload in any supported format.")
            MultipartFile file,
            @RequestParam("categoryId") Long categoryId,
            @RequestParam("creatorId") Long creatorId,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "description", required = false) String description) throws IOException {

        Document document = uploadService.upload(
                file.getBytes(), file.getOriginalFilename(), file.getContentType(),
                categoryId, creatorId, name, description);

        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentUploadResponse.from(document));
    }

}
