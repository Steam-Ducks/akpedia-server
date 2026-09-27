package com.akpedia.server.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.DocumentPreview;
import com.akpedia.server.service.DocumentPreviewService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Feeds the in-app document viewer: the page count first, then each page as an image.
 *
 * <p>Nothing here ever answers the PDF. Every response is {@code no-store}, so the pages are not
 * left behind in the browser's disk cache for anyone to dig out afterwards.
 */
@RestController
@RequestMapping("/api/v1/documents/{id}/preview")
@Tag(name = "Documents")
public class DocumentPreviewController {

    private static final String ERROR_SCHEMA = "application/json";

    private static final String NOSNIFF_HEADER = "X-Content-Type-Options";

    private final DocumentPreviewService previewService;

    public DocumentPreviewController(DocumentPreviewService previewService) {
        this.previewService = previewService;
    }

    @Operation(
            summary = "Describe a document for the in-app viewer",
            description = """
                    Answers the document's name and `page_count`, which is how many pages the viewer may ask
                    for from `/preview/pages/{page}`. Refused exactly as the file route is: 403 when archived,
                    409 while indexing is in flight.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The document's name and number of pages."),
        @ApiResponse(responseCode = "403", description = "The document is archived.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "No such document, or the document has no stored file.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "akpedia-ml is still indexing the document.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<DocumentPreview> describe(@PathVariable("id") Long id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(previewService.describe(id));
    }

    @Operation(
            summary = "Render one page of a document as an image",
            description = """
                    Answers the page, counted from 1, as a PNG rendered on the server, so the viewer can show the
                    document without the PDF ever reaching the browser. `Cache-Control: no-store` keeps the
                    image out of the browser's cache.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The page as a PNG.",
                content = @Content(mediaType = MediaType.IMAGE_PNG_VALUE, schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "403", description = "The document is archived.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "No such document, file, or page.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "akpedia-ml is still indexing the document.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/pages/{page}")
    public ResponseEntity<byte[]> page(@PathVariable("id") Long id, @PathVariable("page") int page) {
        byte[] png = previewService.renderPage(id, page);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .contentLength(png.length)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
                .header(NOSNIFF_HEADER, "nosniff")
                .body(png);
    }

}
