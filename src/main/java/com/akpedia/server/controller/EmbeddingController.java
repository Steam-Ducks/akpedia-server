package com.akpedia.server.controller;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.akpedia.server.client.EmbeddingClient;
import com.akpedia.server.dto.ApiErrorResponse;
import com.akpedia.server.dto.DocumentEmbeddingResponse;
import com.akpedia.server.dto.QueryEmbeddingRequest;
import com.akpedia.server.dto.QueryEmbeddingResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Exposes the embedding capability over HTTP.
 *
 * <p>For now these routes pass through to the embedding service and hand the answer back
 * whole: nothing is stored yet. They exist so the integration can be exercised end to end
 * -- once indexing and search land, this is where the chunks start going to the database
 * instead of back to the caller.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Embeddings", description = "Transforms documents and searches into comparable vectors.")
public class EmbeddingController {

    private static final String ERROR_SCHEMA = "application/json";

    private final EmbeddingClient embeddings;

    public EmbeddingController(EmbeddingClient embeddings) {
        this.embeddings = embeddings;
    }

    /** Extracts, chunks and embeds an uploaded document. */
    @Operation(
            summary = "Transform a document into chunks with vectors",
            description = """
                    Sends the file to the embedding service, which extracts the text, splits it into chunks,
                    and returns one vector per chunk. Only PDF is currently accepted; the limit is 25 MB.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Documento processado."),
        @ApiResponse(responseCode = "413", description = "File exceeds the accepted limit.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "415", description = "Unsupported format.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Unreadable file or no extractable text.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "The embedding service returned an unexpected response.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "The embedding service is unavailable.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping(path = "/documents/embed", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentEmbeddingResponse embedDocument(
            @RequestPart("file")
            @Schema(type = "string", format = "binary", description = "Documento a processar (PDF).")
            MultipartFile file) throws IOException {
        return embeddings.embedDocument(file.getBytes(), file.getOriginalFilename(), file.getContentType());
    }

    /** Embeds a search text into a vector comparable to the document chunks'. */
    @Operation(
            summary = "Transform search text into a vector",
            description = """
                    Returns the search text vector with the same dimensions and model as the chunks,
                    which makes cosine similarity comparisons possible.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Text converted into a vector."),
        @ApiResponse(responseCode = "400", description = "Blank text; rejected before calling the service.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Text rejected by the service (empty or too long).",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "The embedding service returned an unexpected response.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "The embedding service is unavailable.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping(path = "/embeddings/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public QueryEmbeddingResponse embedQuery(@Valid @RequestBody QueryEmbeddingRequest request) {
        return embeddings.embedQuery(request.text());
    }

}
