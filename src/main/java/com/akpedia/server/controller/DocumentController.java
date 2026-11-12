package com.akpedia.server.controller;

import java.io.IOException;
import java.math.BigDecimal;
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
import com.akpedia.server.dto.DocumentAnalysisResponse;
import com.akpedia.server.dto.DocumentFileDescriptor;
import com.akpedia.server.dto.DocumentSummary;
import com.akpedia.server.dto.DocumentUploadResponse;
import com.akpedia.server.dto.PageResponse;
import com.akpedia.server.dto.PendingDocument;
import com.akpedia.server.dto.SubmissionSummary;
import com.akpedia.server.entity.Document;
import com.akpedia.server.security.CurrentUser;
import com.akpedia.server.service.DocumentAnalysisService;
import com.akpedia.server.service.DocumentFileService;
import com.akpedia.server.service.DocumentListService;
import com.akpedia.server.service.DocumentSubmissionService;
import com.akpedia.server.service.DocumentUploadService;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
    private final DocumentListService listService;
    private final DocumentAnalysisService analysisService;
    private final DocumentSubmissionService submissionService;
    private final CurrentUser currentUser;

    public DocumentController(
            DocumentUploadService uploadService,
            DocumentFileService fileService,
            DocumentListService listService,
            DocumentAnalysisService analysisService,
            DocumentSubmissionService submissionService,
            CurrentUser currentUser) {
        this.uploadService = uploadService;
        this.fileService = fileService;
        this.listService = listService;
        this.analysisService = analysisService;
        this.submissionService = submissionService;
        this.currentUser = currentUser;
    }

    @Operation(
            summary = "List the most recently changed documents",
            description = """
                    Answers up to `limit` documents (5 by default, at most 50), the most recently changed first;
                    a document never edited counts from its creation. Lists the same documents search can
                    return -- approved and indexed -- each with the fields a search result carries about
                    the document: `category`, the `responsible_name` of the user who uploaded it, and
                    `updated_at`.

                    A document still under review, rejected or archived is never listed, even once indexed.

                    Only documents in a category the caller's sector may see are listed, read from the sector's
                    permissions on every request: a sector with no category answers an empty list.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Documents, most recently changed first; the list may be empty."),
        @ApiResponse(responseCode = "400", description = "The limit is invalid.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/documents")
    public List<DocumentSummary> recent(@RequestParam(required = false) Integer limit) {
        return listService.recent(limit, currentUser.id());
    }

    @Operation(
            summary = "List the logged-in user's own submissions and where each one stands",
            description = """
                    Answers the documents the logged-in user uploaded, most recently submitted first, each with
                    a status derived for the author rather than the stored one:

                    - `UNDER_REVIEW`: waiting on an approver, whatever the indexing is doing;
                    - `PROCESSING`: approved and being indexed, so search does not reach it yet;
                    - `PUBLISHED`: approved and indexed, so colleagues can find it;
                    - `INDEXING_FAILED`: approved, but indexing failed, so search will not reach it;
                    - `RETURNED`: sent back, with `review_comment` saying why.

                    Unlike `GET /documents` and search, this route shows unapproved documents on purpose --
                    they are the caller's own, and saying what is still under review is the point. It shows
                    nobody else's.

                    `review_comment` and `reviewed_at` are only present on a returned document; every other
                    field is always present, null when it has no value. A document
                    that is still a draft, or that was archived, is not a submission and is not listed.

                    `categoryIds` narrows the listing to one or more categories; with none given, every
                    category is listed.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "One page of submissions; the page may be empty."),
        @ApiResponse(responseCode = "400", description = "page or size is out of bounds.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/documents/submissions")
    public PageResponse<SubmissionSummary> submissions(
            @RequestParam(required = false)
            @Parameter(description = "Zero-based page. Defaults to 0.")
            Integer page,
            @RequestParam(required = false)
            @Parameter(description = "Results per page, from 1 to 50. Defaults to 10.")
            Integer size,
            @RequestParam(required = false)
            @Parameter(description = "One or more category ids to filter by. Defaults to every category.")
            List<Long> categoryIds) {

        return submissionService.submissions(currentUser.id(), page, size, categoryIds);
    }

    @Operation(
            summary = "Suggest a title, description and category for a document, without storing it",
            description = """
                    Answers the title, description and category akpedia-ml reads from the file, so the upload
                    screen can pre-fill the form before anything is sent. The screen never calls akpedia-ml
                    itself: it posts the file here, and this server validates the format, converts it and asks
                    akpedia-ml.

                    `suggested_category_id` and `confidence` are `null` when akpedia-ml has no category to
                    suggest; the screen then leaves the category for the user to pick. The title and
                    description come back either way (`description` is `null` when the document has no
                    paragraph long enough).

                    **Nothing is persisted.** The file is read, converted in memory and discarded, so an
                    analysis the user abandons leaves no document and no rows behind.

                    Accepts PDF, DOC/DOCX and XLS/XLSX only -- a narrower list than the upload, which takes
                    whatever Gotenberg converts. Non-PDF files are converted before being classified.

                    The returned `suggested_category_id` is what `POST /api/v1/documents` takes as
                    `suggestedCategoryId`, and `confidence` as `categoryConfidence`, so both can be handed
                    straight back without a lookup.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
                description = "The suggested title, description and category (null when there is no suggestion)."),
        @ApiResponse(responseCode = "400", description = "Invalid request (empty file or missing part).",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "File exceeds the accepted limit.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "415", description = "Format is not one of PDF, DOC/DOCX or XLS/XLSX.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Gotenberg received the file but rejected the conversion.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "The classifier suggested a category outside the catalog.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503",
                description = "The classifier is unavailable; the screen should let the user choose the category.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping(path = "/documents/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentAnalysisResponse analyze(
            @RequestPart("file")
            @Schema(type = "string", format = "binary", description = "File to analyze: PDF, DOC/DOCX or XLS/XLSX.")
            MultipartFile file) throws IOException {

        return analysisService.analyze(file.getBytes(), file.getOriginalFilename(), file.getContentType());
    }

    @Operation(
            summary = "List the documents awaiting approval",
            description = """
                    The review queue: every document in `PENDING_APPROVAL`, the oldest upload first, `size`
                    per page (20 by default, at most 50) starting from `page` 0. Each document carries who
                    uploaded it and from which sector, when, the `description` and `category` the author
                    sent, and the category the classifier suggested with its `confidence` -- both null when
                    the upload carried no suggestion. A pending document's
                    file opens through `/documents/{id}/file`, which is how the approver reads it.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "One page of the queue; `content` is empty past the last page."),
        @ApiResponse(responseCode = "400", description = "The page or the size is invalid.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/documents/pending")
    public PageResponse<PendingDocument> pending(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return listService.pending(page, size);
    }

    @Operation(
            summary = "Upload a document, convert it to PDF, and store it pending approval",
            description = """
                    Accepts any format Gotenberg can convert through LibreOffice (docx, xlsx, pptx, odt, rtf,
                    images, plain text...). Uploaded PDFs are stored directly without reconversion.
                    The stored binary is always the resulting PDF; the original format is not retained.

                    The document is stored with `status: PENDING_APPROVAL` and `processing_status: PENDING`.
                    Nothing is sent to akpedia-ml here: the document is only embedded for search once it is
                    approved, so it has no rows in `embeddings` until then. It can be opened and previewed
                    in the meantime, which is how the approver reads it.

                    `suggestedCategoryId` and `categoryConfidence` record the category the classifier
                    suggested and how sure it was, for the approver to confirm or correct.

                    The document is attributed to the logged-in user.""")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Document converted and stored, awaiting approval."),
        @ApiResponse(responseCode = "400", description = "Invalid request (empty file, missing parameter, or invalid parameter).",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "The specified category or suggested category does not exist.",
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
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "suggestedCategoryId", required = false)
            @Parameter(description = "Category the classifier suggested; one of the catalog's categories.")
            Long suggestedCategoryId,
            @RequestParam(value = "categoryConfidence", required = false)
            @Parameter(description = "Confidence of the suggestion, from 0 to 1. Requires `suggestedCategoryId`.")
            BigDecimal categoryConfidence) throws IOException {

        Document document = uploadService.upload(
                file.getBytes(), file.getOriginalFilename(), file.getContentType(),
                categoryId, currentUser.id(), name, description, suggestedCategoryId, categoryConfidence);

        return ResponseEntity.status(HttpStatus.CREATED).body(DocumentUploadResponse.from(document));
    }

    @Operation(
            summary = "Open the stored file of a document",
            description = """
                    Answers the stored bytes with the media type recorded for the document (always
                    `application/pdf`, since the upload converts everything) and `Content-Disposition: inline`,
                    so pointing a browser at this URL renders the document instead of downloading it. The file
                    name carries the `.pdf` extension even when the document was named without one.

                    A document in a category the caller's sector may not see answers 403 `category_forbidden`,
                    before anything about its state. Two exceptions open it anyway: its author, and an `APPROVER`
                    or `ADMIN` while it awaits approval, since the review queue lists every pending document.

                    Two documents are not openable: an archived one (403), which is out of circulation, and one
                    akpedia-ml is indexing right now (409) -- the same request works once the indexing lands.
                    A document awaiting approval opens, since the approver has to read it, and so does one
                    whose indexing *failed*: the PDF is stored at upload, so only search is missing it.

                    The response is private and revalidated on every use, and carries an `ETag` and
                    `Last-Modified`: a browser that already holds the document asks again and gets a 304 with no
                    bytes, while one whose document has since been archived is refused instead of going on
                    showing its copy -- and so is one whose category has since been taken from the sector.
                    `Accept-Ranges: bytes` is advertised and a `Range` request answers 206, so
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
        @ApiResponse(responseCode = "403", description = "Category hidden from the caller's sector (`category_forbidden`), or archived (`document_archived`).",
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

        DocumentFileDescriptor file = fileService.describe(id, currentUser.viewer());
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

        DocumentFileDescriptor file = fileService.describe(id, currentUser.viewer());
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
     * <p>Asked only after the document has been described, so an archived document -- or one the
     * caller's sector may no longer see -- is refused instead of having its cached copy confirmed -- which is what makes caching this response
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
