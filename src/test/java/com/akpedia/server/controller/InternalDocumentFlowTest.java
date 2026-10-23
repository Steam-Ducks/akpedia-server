package com.akpedia.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.akpedia.server.config.DefaultUserProvider;
import com.akpedia.server.dto.ApproveDocumentRequest;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.DocumentFile;
import com.akpedia.server.entity.Embedding;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.repository.CategoryRepository;
import com.akpedia.server.repository.DocumentFileRepository;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.EmbeddingRepository;
import com.akpedia.server.repository.UserRepository;
import com.akpedia.server.service.DocumentReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The worker's side of the indexing, over HTTP, on the real services, transactions and database,
 * with only Redis replaced: an approved document is downloaded, its result or failure is stored,
 * the stream message is acknowledged only after the commit, and the document reaches search.
 *
 * <p>Not {@code @Transactional}, as in {@code DocumentReviewFlowTest}: each call has to commit on
 * its own for the acknowledgement to follow. What a test creates is deleted after it.
 */
@SpringBootTest(properties = {
    "akpedia.processing.retry-interval=1h",
    "akpedia.internal.token=" + InternalDocumentFlowTest.TOKEN})
@AutoConfigureMockMvc
class InternalDocumentFlowTest {

    static final String TOKEN = "token-de-teste";

    private static final String STREAM = "documents:processing";
    private static final String GROUP = "ml-workers";
    private static final String MESSAGE_ID = "1761234567890-0";
    private static final byte[] PDF = "%PDF-1.4 conteudo".getBytes();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private DocumentReviewService reviewService;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private DocumentFileRepository documentFiles;
    @Autowired
    private EmbeddingRepository embeddings;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private UserRepository users;
    @Autowired
    private DefaultUserProvider defaultUser;

    @MockBean
    private StringRedisTemplate redis;

    private StreamOperations<String, Object, Object> stream;
    private final List<Long> created = new ArrayList<>();
    /** Unique per test, so the search below only ever sees this test's vectors. */
    private final String model = "modelo-de-teste-" + UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stream = mock(StreamOperations.class);
        given(redis.opsForStream()).willReturn(stream);
    }

    @AfterEach
    void cleanUp() {
        // File and embeddings go with the document (ON DELETE CASCADE).
        documents.deleteAllById(created);
    }

    /** A document approved by the approver, so {@code PROCESSING} and waiting on the worker. */
    private Long approvedDocument() {
        Category category = categories.findByName("Tecnico").orElseThrow();
        Document document = documents.save(new Document("manual-indexado.pdf", "application/pdf", (long) PDF.length,
                category, users.findById(defaultUser.userId()).orElseThrow(), DocumentStatus.PENDING_APPROVAL));
        created.add(document.getId());
        documentFiles.save(new DocumentFile(document, PDF));
        reviewService.approve(document.getId(), ApproveDocumentRequest.NONE);
        return document.getId();
    }

    private Document reload(Long id) {
        return documents.findById(id).orElseThrow();
    }

    private static List<Float> vector(float value) {
        return Collections.nCopies(Embedding.VECTOR_DIMENSIONS, value);
    }

    private String result(String firstChunk, List<Float> vector) throws Exception {
        return json.writeValueAsString(Map.of(
                "filename", "manual-indexado.pdf",
                "model", Map.of("name", model, "dimensions", Embedding.VECTOR_DIMENSIONS, "normalized", true),
                "chunk_count", 2,
                "chunks", List.of(
                        Map.of("index", 0, "text", firstChunk, "embedding", vector),
                        Map.of("index", 1, "text", "segundo trecho", "embedding", vector)),
                "messageId", MESSAGE_ID));
    }

    private String failure() throws Exception {
        return json.writeValueAsString(Map.of(
                "code", "no_extractable_text", "message", "O PDF nao tem texto.", "messageId", MESSAGE_ID));
    }

    private ResultActions postWithToken(String path, String body) throws Exception {
        return mvc.perform(post(path)
                .header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** Ids of the approved, indexed documents a search over this test's model returns. */
    private List<Long> searchableDocuments() {
        String query = IntStream.range(0, Embedding.VECTOR_DIMENSIONS)
                .mapToObj(i -> "1.0")
                .collect(Collectors.joining(",", "[", "]"));
        return embeddings.search(query, model, Embedding.VECTOR_DIMENSIONS, -1.0, false, List.of(-1L), 10)
                .stream()
                .map(row -> ((Number) row[0]).longValue())
                .toList();
    }

    @Test
    @DisplayName("without the token, or with a wrong one, every internal route answers 401 and changes nothing")
    void callWithoutTokenIsRefused() throws Exception {
        Long id = approvedDocument();

        mvc.perform(get("/internal/documents/{id}/file", id))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_internal_token"));
        mvc.perform(post("/internal/documents/{id}/embeddings", id)
                        .header("X-Internal-Token", "token-errado")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(result("primeiro trecho", vector(0.5f))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/documents/{id}/processing-failure", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failure()))
                .andExpect(status().isUnauthorized());

        assertThat(reload(id).getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(embeddings.findByDocumentIdOrderByChunkIndex(id)).isEmpty();
        verify(stream, never()).acknowledge(anyString(), anyString(), any(String[].class));
    }

    @Test
    @DisplayName("the worker downloads the PDF of a document while it is PROCESSING")
    void fileIsServedToTheWorkerWhileProcessing() throws Exception {
        Long id = approvedDocument();

        mvc.perform(get("/internal/documents/{id}/file", id).header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(
                        "filename*=UTF-8''manual-indexado.pdf")))
                .andExpect(content().bytes(PDF));

        mvc.perform(get("/internal/documents/{id}/file", -1L).header("X-Internal-Token", TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the embeddings are stored, the document is COMPLETED and in search, and the message is "
            + "acknowledged only after the commit")
    void embeddingsReleaseTheDocumentForSearch() throws Exception {
        Long id = approvedDocument();
        AtomicReference<ProcessingStatus> statusWhenAcked = new AtomicReference<>();
        willAnswer(call -> {
            statusWhenAcked.set(reload(id).getProcessingStatus());
            return 1L;
        }).given(stream).acknowledge(STREAM, GROUP, MESSAGE_ID);
        assertThat(searchableDocuments()).doesNotContain(id);

        postWithToken("/internal/documents/" + id + "/embeddings", result("primeiro trecho", vector(0.5f)))
                .andExpect(status().isOk());

        Document indexed = reload(id);
        assertThat(indexed.getStatus()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(indexed.getApprovedAt()).isNotNull();
        assertThat(indexed.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(indexed.getProcessedAt()).isNotNull();
        assertThat(indexed.getProcessingError()).isNull();
        assertThat(embeddings.findByDocumentIdOrderByChunkIndex(id))
                .extracting(Embedding::getChunkIndex, Embedding::getContent, Embedding::getModelName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "primeiro trecho", model),
                        org.assertj.core.groups.Tuple.tuple(1, "segundo trecho", model));
        // Read through a separate query from the acknowledgement: the commit had already happened.
        assertThat(statusWhenAcked.get()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(searchableDocuments()).containsExactly(id);
    }

    @Test
    @DisplayName("a repeated call back answers 200 without rewriting, and acknowledges the message again")
    void duplicateCallbackRewritesNothing() throws Exception {
        Long id = approvedDocument();
        postWithToken("/internal/documents/" + id + "/embeddings", result("primeiro trecho", vector(0.5f)))
                .andExpect(status().isOk());
        Document first = reload(id);
        List<Long> firstRows = embeddings.findByDocumentIdOrderByChunkIndex(id).stream().map(Embedding::getId).toList();

        postWithToken("/internal/documents/" + id + "/embeddings", result("outro texto", vector(0.25f)))
                .andExpect(status().isOk());
        postWithToken("/internal/documents/" + id + "/processing-failure", failure())
                .andExpect(status().isOk());

        Document again = reload(id);
        assertThat(again.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(again.getProcessedAt()).isEqualTo(first.getProcessedAt());
        assertThat(again.getProcessingError()).isNull();
        List<Embedding> rows = embeddings.findByDocumentIdOrderByChunkIndex(id);
        assertThat(rows).extracting(Embedding::getId).isEqualTo(firstRows);
        assertThat(rows.get(0).getContent()).isEqualTo("primeiro trecho");
        verify(stream, times(3)).acknowledge(STREAM, GROUP, MESSAGE_ID);
    }

    @Test
    @DisplayName("a failure is stored as FAILED with its code and message, then acknowledged")
    void failureIsRecorded() throws Exception {
        Long id = approvedDocument();

        postWithToken("/internal/documents/" + id + "/processing-failure", failure())
                .andExpect(status().isOk());

        Document failed = reload(id);
        assertThat(failed.getProcessingStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(failed.getProcessingError()).isEqualTo("no_extractable_text: O PDF nao tem texto.");
        assertThat(failed.getProcessedAt()).isNull();
        verify(stream).acknowledge(STREAM, GROUP, MESSAGE_ID);
        assertThat(searchableDocuments()).doesNotContain(id);
    }

    @Test
    @DisplayName("a document whose indexing failed is indexed by a later successful call back")
    void successAfterFailureCompletesTheDocument() throws Exception {
        Long id = approvedDocument();
        postWithToken("/internal/documents/" + id + "/processing-failure", failure())
                .andExpect(status().isOk());

        postWithToken("/internal/documents/" + id + "/embeddings", result("primeiro trecho", vector(0.5f)))
                .andExpect(status().isOk());

        Document indexed = reload(id);
        assertThat(indexed.getProcessingStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(indexed.getProcessingError()).isNull();
        assertThat(searchableDocuments()).containsExactly(id);
    }

    @Test
    @DisplayName("vectors that do not fit the index are refused with 400, nothing is stored and nothing acknowledged")
    void vectorsOfTheWrongSizeAreRefused() throws Exception {
        Long id = approvedDocument();

        postWithToken("/internal/documents/" + id + "/embeddings", result("primeiro trecho", List.of(0.1f, 0.2f)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_embeddings"));
        postWithToken("/internal/documents/" + id + "/embeddings",
                result("primeiro trecho", vector(0.5f)).replace(MESSAGE_ID, "nao-e-um-id"))
                .andExpect(status().isBadRequest());

        assertThat(reload(id).getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(embeddings.findByDocumentIdOrderByChunkIndex(id)).isEmpty();
        verify(stream, never()).acknowledge(anyString(), anyString(), any(String[].class));
    }

    @Test
    @DisplayName("embeddings for a document that is not approved are refused with 409")
    void embeddingsOfAnUnapprovedDocumentAreRefused() throws Exception {
        Category category = categories.findByName("Tecnico").orElseThrow();
        Document pending = documents.save(new Document("pendente.pdf", "application/pdf", 1L,
                category, users.findById(defaultUser.userId()).orElseThrow(), DocumentStatus.PENDING_APPROVAL));
        created.add(pending.getId());

        postWithToken("/internal/documents/" + pending.getId() + "/embeddings",
                result("primeiro trecho", vector(0.5f)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("document_not_approved"));

        assertThat(embeddings.findByDocumentIdOrderByChunkIndex(pending.getId())).isEmpty();
        verify(stream, never()).acknowledge(anyString(), anyString(), any(String[].class));
    }

    @Test
    @DisplayName("a call back for a document that no longer exists answers 404 and acknowledges the message")
    void missingDocumentIsAcknowledged() throws Exception {
        postWithToken("/internal/documents/-1/embeddings", result("primeiro trecho", vector(0.5f)))
                .andExpect(status().isNotFound());
        postWithToken("/internal/documents/-1/processing-failure", failure())
                .andExpect(status().isNotFound());

        verify(stream, times(2)).acknowledge(eq(STREAM), eq(GROUP), eq(MESSAGE_ID));
    }
}
