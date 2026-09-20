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
@Tag(name = "Embeddings", description = "Transforma documentos e buscas em vetores comparáveis entre si.")
public class EmbeddingController {

    private static final String ERROR_SCHEMA = "application/json";

    private final EmbeddingClient embeddings;

    public EmbeddingController(EmbeddingClient embeddings) {
        this.embeddings = embeddings;
    }

    /** Extracts, chunks and embeds an uploaded document. */
    @Operation(
            summary = "Transforma um documento em chunks com vetores",
            description = """
                    Envia o arquivo ao serviço de embeddings, que extrai o texto, divide em trechos
                    e devolve um vetor por trecho. Hoje só PDF é aceito; o limite é 25 MB.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Documento processado."),
        @ApiResponse(responseCode = "413", description = "Arquivo maior que o limite aceito.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "415", description = "Formato não suportado.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Arquivo ilegível ou sem texto extraível.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "O serviço de embeddings respondeu algo inesperado.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "O serviço de embeddings está fora do ar.",
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
            summary = "Transforma um texto de busca em vetor",
            description = """
                    Devolve o vetor do texto buscado, na mesma largura e produzido pelo mesmo modelo
                    dos chunks -- é o que permite comparar os dois por similaridade de cosseno.""")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Texto vetorizado."),
        @ApiResponse(responseCode = "400", description = "Texto em branco; recusado antes de chamar o serviço.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "Texto recusado pelo serviço (vazio ou longo demais).",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "O serviço de embeddings respondeu algo inesperado.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "O serviço de embeddings está fora do ar.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping(path = "/embeddings/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public QueryEmbeddingResponse embedQuery(@Valid @RequestBody QueryEmbeddingRequest request) {
        return embeddings.embedQuery(request.text());
    }

}
