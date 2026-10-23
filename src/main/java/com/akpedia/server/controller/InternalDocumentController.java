package com.akpedia.server.controller;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.config.InternalTokenFilter;
import com.akpedia.server.dto.DocumentFileDescriptor;
import com.akpedia.server.dto.IndexedDocumentRequest;
import com.akpedia.server.dto.ProcessingFailureRequest;
import com.akpedia.server.service.DocumentFileService;
import com.akpedia.server.service.DocumentIndexingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The routes the akpedia-ml indexing worker calls: it never touches the database, so it reads an
 * approved document's file from here and sends the result back here.
 *
 * <p>Every route requires the {@code X-Internal-Token} header; see {@link InternalTokenFilter}.
 */
@RestController
@RequestMapping("/internal/documents")
@Tag(name = "Internal", description = "Called by the akpedia-ml worker only; require the X-Internal-Token header, set to INTERNAL_API_TOKEN.")
public class InternalDocumentController {

    private final DocumentFileService fileService;
    private final DocumentIndexingService indexingService;

    public InternalDocumentController(DocumentFileService fileService, DocumentIndexingService indexingService) {
        this.fileService = fileService;
        this.indexingService = indexingService;
    }

    @Operation(summary = "Download the stored PDF of a document to index it",
            description = "Unlike `GET /api/v1/documents/{id}/file`, answers while the document is `PROCESSING`.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The stored file, as an attachment with its name."),
        @ApiResponse(responseCode = "401", description = "Missing or wrong token."),
        @ApiResponse(responseCode = "404", description = "No such document, or it has no stored file.")
    })
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable("id") Long id) {
        DocumentFileDescriptor file = fileService.describeForIndexing(id);
        ByteArrayResource content = new ByteArrayResource(fileService.contentOf(file));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(file.contentType())
                .contentLength(content.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(content);
    }

    @Operation(summary = "Store a document's embeddings and release it for search",
            description = """
                    Replaces the document's embeddings with the ones sent, in one transaction, and marks it
                    `processing_status: COMPLETED` with `processed_at`, clearing `processing_error`. Once that
                    commits, the stream message `messageId` is acknowledged (`XACK`).

                    A document already `COMPLETED` answers 200 without writing anything: the worker sends
                    the same result again when a message is delivered twice.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Stored, or already stored."),
        @ApiResponse(responseCode = "400", description = "Malformed body, or vectors that do not fit the index."),
        @ApiResponse(responseCode = "401", description = "Missing or wrong token."),
        @ApiResponse(responseCode = "404", description = "No such document; the message is acknowledged anyway."),
        @ApiResponse(responseCode = "409", description = "The document is not approved.")
    })
    @PostMapping("/{id}/embeddings")
    public void embeddings(@PathVariable("id") Long id, @RequestBody @Valid IndexedDocumentRequest result) {
        indexingService.storeEmbeddings(id, result);
    }

    @Operation(summary = "Record that a document could not be indexed",
            description = """
                    Marks the document `processing_status: FAILED`, with `code: message` as its
                    `processing_error`, and then acknowledges the stream message `messageId`.

                    A document already `COMPLETED` answers 200 and keeps its embeddings.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Recorded, or the document was already indexed."),
        @ApiResponse(responseCode = "400", description = "Malformed body."),
        @ApiResponse(responseCode = "401", description = "Missing or wrong token."),
        @ApiResponse(responseCode = "404", description = "No such document; the message is acknowledged anyway.")
    })
    @PostMapping("/{id}/processing-failure")
    public void processingFailure(@PathVariable("id") Long id, @RequestBody @Valid ProcessingFailureRequest failure) {
        indexingService.recordFailure(id, failure);
    }
}
