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
@Tag(name = "Documents", description = "Upload de documentos: converte para PDF, salva e indexa no akpedia-ml.")
public class DocumentController {

    private static final String ERROR_SCHEMA = "application/json";

    private final DocumentUploadService uploadService;

    public DocumentController(DocumentUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @Operation(
            summary = "Envia um documento, convertendo-o para PDF e indexando no akpedia-ml",
            description = """
                    Aceita qualquer formato que o Gotenberg saiba converter via LibreOffice (docx, xlsx,
                    pptx, odt, rtf, imagens, texto puro...). Um PDF enviado passa direto, sem reconversão.
                    O binário salvo é sempre o PDF resultante -- o formato original não é retido.

                    Depois de salvo, o PDF é enviado ao akpedia-ml para gerar os embeddings, que ficam em
                    `embeddings`. Uma falha nessa etapa não desfaz o upload: o documento já salvo fica
                    com `processing_status: FAILED` e o motivo em `processing_error`, para tentar de novo
                    mais tarde -- só a conversão para PDF é obrigatória para a resposta ser 201.""")
    @ApiResponses({
        @ApiResponse(responseCode = "201",
                description = "Documento convertido e salvo; processing_status indica se a indexação no akpedia-ml deu certo."),
        @ApiResponse(responseCode = "400", description = "Requisição inválida (arquivo vazio, parâmetro ausente ou inválido).",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Categoria ou usuário informado não existe.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "413", description = "Arquivo maior que o limite aceito.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "422", description = "O Gotenberg recebeu o arquivo, mas recusou a conversão.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "502", description = "O Gotenberg respondeu algo inesperado.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "O Gotenberg está fora do ar.",
                content = @Content(mediaType = ERROR_SCHEMA, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping(path = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentUploadResponse> upload(
            @RequestPart("file")
            @Schema(type = "string", format = "binary", description = "Arquivo a enviar, em qualquer formato suportado.")
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
