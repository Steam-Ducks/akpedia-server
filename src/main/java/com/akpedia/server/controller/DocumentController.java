package com.akpedia.server.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;

import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.DocumentFileDescriptor;
import com.akpedia.server.dto.DocumentUploadResponse;
import com.akpedia.server.entity.Document;
import com.akpedia.server.service.DocumentFileService;
import com.akpedia.server.service.DocumentUploadService;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Uploads documents into the database and serves them back.
 *
 * <p>Whatever format arrives, the file that gets stored is always a PDF: this is what lets
 * every other document-processing feature -- embeddings included -- assume a single format
 * instead of handling one converter per file type. The upload also triggers the embedding
 * of that PDF through akpedia-ml, so a stored document is a searchable one.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Documents", description = "Document upload and retrieval: convert to PDF, store, index with akpedia-ml, and serve back.")
public class DocumentController {

    private static final String ERROR_SCHEMA = "application/json";

    /** Keeps the browser on the declared media type instead of sniffing the bytes for another one. */
    private static final String NOSNIFF_HEADER = "X-Content-Type-Options";

    private final DocumentUploadService uploadService;
    private final DocumentFileService fileService;

    public DocumentController(DocumentUploadService uploadService, DocumentFileService fileService) {
        this.uploadService = uploadService;
        this.fileService = fileService;
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

    @Operation(
            summary = "Open the stored file of a document",
            description = """
                    Answers the stored bytes with the media type recorded for the document (always
                    `application/pdf`, since the upload converts everything) and `Content-Disposition: inline`,
                    so pointing a browser at this URL renders the document instead of downloading it. The file
                    name carries the `.pdf` extension even when the document was named without one.

                    Two documents are not openable: an archived one (403), which is out of circulation, and one
                    whose indexing is still in flight (409) -- the same request works once the indexing lands.
                    A document whose indexing *failed* does open: its PDF was stored before that step ran, so
                    the bytes are intact and only search is missing them.

                    The response is private and revalidated on every use, and carries an `ETag` and
                    `Last-Modified`: a browser that already holds the document asks again and gets a 304 with no
                    bytes, while one whose document has since been archived is refused instead of going on
                    showing its copy. `Accept-Ranges: bytes` is advertised and a `Range` request answers 206, so
                    a PDF viewer can fetch a document by parts instead of waiting for the whole file.
                    `X-Content-Type-Options: nosniff` keeps the browser on the declared type.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The stored file, inline, with its media type and name.",
                content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                        schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "206", description = "The requested byte range of the file.",
                content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                        schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(responseCode = "304", description = "The caller's copy is current; no body is sent."),
        @ApiResponse(responseCode = "400", description = "The id in the path is not a number.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "The document is archived.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "No such document, or the document has no stored file.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "409", description = "akpedia-ml is still indexing the document.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "416", description = "The requested range does not fit the file.")
    })
    @GetMapping("/documents/{id}/file")
    public ResponseEntity<Resource> file(
            @PathVariable("id")
            @Schema(description = "Identifier of the document whose file is being opened.")
            Long id,
            WebRequest request) {

        DocumentFileDescriptor file = fileService.describe(id);
        if (isUnchanged(file, request)) {
            return null;
        }

        ByteArrayResource content = new ByteArrayResource(fileService.contentOf(file));
        return headers(file, ResponseEntity.ok())
                .contentLength(content.contentLength())
                .body(content);
    }

    /**
     * The requested slice of the file, for a viewer that fetches a document by parts.
     *
     * <p>A separate handler, taken by the {@code Range} header alone, because the return type is
     * what tells Spring to write byte regions: a method that could answer either shape would
     * declare neither well enough for the converter to pick it. Hidden from the OpenAPI document
     * for the same reason it exists -- it is the same resource as above, whose 206 and 416 are
     * already described there.
     *
     * <p>A range that cannot be satisfied answers 416 carrying the real size, which is how the
     * caller learns what it may ask for instead. Spring writes {@code Content-Range} for a single
     * region and {@code multipart/byteranges} for several, so neither is assembled here.
     */
    @Hidden
    @GetMapping(value = "/documents/{id}/file", headers = "Range")
    public ResponseEntity<List<ResourceRegion>> filePart(
            @PathVariable("id") Long id,
            @RequestHeader(HttpHeaders.RANGE) String range,
            WebRequest request) {

        DocumentFileDescriptor file = fileService.describe(id);
        if (isUnchanged(file, request)) {
            return null;
        }

        ByteArrayResource content = new ByteArrayResource(fileService.contentOf(file));
        List<ResourceRegion> regions;
        try {
            regions = HttpRange.toResourceRegions(HttpRange.parseRanges(range), content);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + content.contentLength())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .build();
        }

        return headers(file, ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)).body(regions);
    }

    /**
     * Whether the caller's copy is still the current one.
     *
     * <p>Asked only after the document has been described, so an archived document is refused
     * instead of having its cached copy confirmed -- which is what makes caching this response
     * safe in the first place.
     */
    private static boolean isUnchanged(DocumentFileDescriptor file, WebRequest request) {
        return request.checkNotModified(file.etag(), file.lastModified().toEpochMilli());
    }

    /** The headers every answer to this route carries, whole file or slice of one. */
    private static BodyBuilder headers(DocumentFileDescriptor file, BodyBuilder response) {
        ContentDisposition disposition = dispositionFor(file)
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();

        return response
                .contentType(file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(NOSNIFF_HEADER, "nosniff")
                .eTag(file.etag())
                .lastModified(file.lastModified())
                .cacheControl(CacheControl.noCache().cachePrivate());
    }

    /**
     * Renders inline only what is meant to be rendered.
     *
     * <p>Every stored file is a PDF, so in practice this is always {@code inline}. The branch is
     * there because the media type comes from a database column: were anything else ever to land
     * in it, serving it inline would let the API's own origin render markup it did not write.
     * Anything that is not a PDF is offered as a download instead.
     */
    private static ContentDisposition.Builder dispositionFor(DocumentFileDescriptor file) {
        return MediaType.APPLICATION_PDF.equalsTypeAndSubtype(file.contentType())
                ? ContentDisposition.inline()
                : ContentDisposition.attachment();
    }

}
