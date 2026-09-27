package com.akpedia.server.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.ConnectException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.akpedia.server.client.EmbeddingClient;
import com.akpedia.server.dto.DocumentChunk;
import com.akpedia.server.dto.DocumentEmbeddingResponse;
import com.akpedia.server.dto.EmbeddingErrorResponse;
import com.akpedia.server.dto.EmbeddingModelInfo;
import com.akpedia.server.dto.QueryEmbeddingResponse;
import com.akpedia.server.exception.ApiExceptionHandler;
import com.akpedia.server.exception.EmbeddingRejectedException;
import com.akpedia.server.exception.EmbeddingUnavailableException;

/**
 * Exercises the HTTP layer with the embedding client mocked.
 *
 * <p>What matters here is the translation: which status each downstream failure becomes,
 * and whether the {@code code} survives the trip out to the caller.
 */
@WebMvcTest(controllers = EmbeddingController.class)
@Import(ApiExceptionHandler.class)
class EmbeddingControllerTest {

    private static final EmbeddingModelInfo MODEL = new EmbeddingModelInfo("e5-small", 3, true);

    @Autowired
    private MockMvc mvc;

    @MockBean
    private EmbeddingClient embeddings;

    @Test
    @DisplayName("POST /api/v1/documents/embed returns the chunks the service produced")
    void embedDocumentReturnsChunks() throws Exception {
        given(embeddings.embedDocument(any(), eq("manual.pdf"), eq("application/pdf")))
                .willReturn(new DocumentEmbeddingResponse("manual.pdf", MODEL, 1,
                        List.of(new DocumentChunk(0, "Primeiro trecho.", List.of(0.1f, 0.2f, 0.3f)))));

        mvc.perform(multipart("/api/v1/documents/embed")
                        .file(new MockMultipartFile("file", "manual.pdf", "application/pdf", "conteudo".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("manual.pdf"))
                .andExpect(jsonPath("$.chunk_count").value(1))
                .andExpect(jsonPath("$.model.dimensions").value(3))
                .andExpect(jsonPath("$.chunks[0].text").value("Primeiro trecho."))
                .andExpect(jsonPath("$.chunks[0].embedding.length()").value(3));
    }

    @Test
    @DisplayName("POST /api/v1/embeddings/query returns the search vector")
    void embedQueryReturnsVector() throws Exception {
        given(embeddings.embedQuery("prazo de garantia"))
                .willReturn(new QueryEmbeddingResponse(MODEL, List.of(0.1f, 0.2f, 0.3f)));

        mvc.perform(post("/api/v1/embeddings/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"prazo de garantia\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model.name").value("e5-small"))
                .andExpect(jsonPath("$.embedding.length()").value(3));
    }

    @Test
    @DisplayName("the service being down answers 503, not 500")
    void unavailableBecomes503() throws Exception {
        willThrow(new EmbeddingUnavailableException(
                "The embedding service is unreachable at http://ml:8000/api/v1/embeddings/query.",
                new ConnectException("Connection refused")))
                .given(embeddings).embedQuery("prazo de garantia");

        mvc.perform(post("/api/v1/embeddings/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"prazo de garantia\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("embedding_service_unavailable"))
                .andExpect(jsonPath("$.message").value(containsString("unreachable")));
    }

    @Test
    @DisplayName("a downstream 415 reaches the caller as 415, with its code and formats")
    void rejectedKeepsClientErrorStatus() throws Exception {
        willThrow(new EmbeddingRejectedException(
                "refused",
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                new EmbeddingErrorResponse("unsupported_format", "No extractor handles '.xyz'.", List.of(".pdf")),
                null))
                .given(embeddings).embedDocument(any(), eq("planilha.xyz"), any());

        mvc.perform(multipart("/api/v1/documents/embed")
                        .file(new MockMultipartFile(
                                "file", "planilha.xyz", "application/octet-stream", new byte[] {1})))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_format"))
                .andExpect(jsonPath("$.supported_extensions[0]").value(".pdf"));
    }

    @Test
    @DisplayName("a downstream 500 becomes 502: the caller's request was fine")
    void rejectedServerErrorBecomes502() throws Exception {
        willThrow(new EmbeddingRejectedException("refused", HttpStatus.INTERNAL_SERVER_ERROR, null, null))
                .given(embeddings).embedQuery("prazo de garantia");

        mvc.perform(post("/api/v1/embeddings/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"prazo de garantia\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("embedding_service_error"));
    }

    @Test
    @DisplayName("a blank search text is refused here, without calling the service")
    void blankQueryIsRefusedLocally() throws Exception {
        mvc.perform(post("/api/v1/embeddings/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));

        verifyNoInteractions(embeddings);
    }

    @Test
    @DisplayName("the error body carries no format list when the failure is not about the format")
    void errorBodyOmitsFormatsWhenAbsent() throws Exception {
        willThrow(new EmbeddingRejectedException(
                "refused",
                HttpStatus.UNPROCESSABLE_ENTITY,
                new EmbeddingErrorResponse("empty_query", "The search text is empty.", null),
                null))
                .given(embeddings).embedQuery("aaaaa");

        mvc.perform(post("/api/v1/embeddings/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"aaaaa\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("empty_query"))
                .andExpect(jsonPath("$.supported_extensions").doesNotExist());
    }

}
