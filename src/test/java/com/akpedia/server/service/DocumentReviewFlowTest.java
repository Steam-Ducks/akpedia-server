package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.akpedia.server.config.DefaultUserProvider;
import com.akpedia.server.dto.ApproveDocumentRequest;
import com.akpedia.server.entity.Category;
import com.akpedia.server.entity.Document;
import com.akpedia.server.entity.DocumentFile;
import com.akpedia.server.entity.enums.DocumentStatus;
import com.akpedia.server.entity.enums.ProcessingStatus;
import com.akpedia.server.exception.CategoryNotFoundException;
import com.akpedia.server.repository.CategoryRepository;
import com.akpedia.server.repository.DocumentFileRepository;
import com.akpedia.server.repository.DocumentRepository;
import com.akpedia.server.repository.UserRepository;

/**
 * Both decisions end to end, on the real service, transactions and database, with only Redis
 * replaced: what is committed, and what reaches the stream as a result.
 *
 * <p>Not {@code @Transactional} on purpose: the point is that each decision commits on its own,
 * since that commit is what releases the id to the stream. What a test creates is deleted after it.
 * The scheduled retry is pushed out of the way so it cannot send anything behind a test's back;
 * the tests that need it run it themselves.
 */
@SpringBootTest(properties = "akpedia.processing.retry-interval=1h")
class DocumentReviewFlowTest {

    private static final String STREAM = "documents:processing";

    @Autowired
    private DocumentReviewService reviewService;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private DocumentFileRepository documentFiles;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private UserRepository users;
    @Autowired
    private DefaultUserProvider defaultUser;
    @Autowired
    private ProcessingRetry retry;

    @MockBean
    private StringRedisTemplate redis;

    private StreamOperations<String, Object, Object> stream;
    private final List<Long> created = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stream = mock(StreamOperations.class);
        given(redis.opsForStream()).willReturn(stream);
    }

    @AfterEach
    void cleanUp() {
        // The file goes with the document (ON DELETE CASCADE).
        documents.deleteAllById(created);
    }

    /** A document waiting on a decision, with its file, both already committed. */
    private Document pendingWithFile() {
        Category category = categories.findByName("Tecnico").orElseThrow();
        Document document = documents.save(new Document("manual-de-fluxo.pdf", "application/pdf", 8L,
                category, users.findById(defaultUser.userId()).orElseThrow(), DocumentStatus.PENDING_APPROVAL));
        created.add(document.getId());
        documentFiles.save(new DocumentFile(document, "%PDF-1.4".getBytes()));
        return document;
    }

    private Document reload(Long id) {
        return documents.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("an approval with corrections is committed, and only then is the id added to the stream")
    void approvalCommitsAndThenPublishes() {
        Long id = pendingWithFile().getId();
        Category corrected = categories.findByName("Qualidade").orElseThrow();

        reviewService.approve(id, new ApproveDocumentRequest("Manual revisado", null, corrected.getId()));

        Document approved = reload(id);
        assertThat(approved.getStatus()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(approved.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(approved.getName()).isEqualTo("Manual revisado");
        assertThat(approved.getApprovedAt()).isNotNull();
        assertThat(approved.getReviewedAt()).isNotNull();
        // Reading the id of a lazy association does not need the session that loaded it.
        assertThat(approved.getCategory().getId()).isEqualTo(corrected.getId());
        assertThat(approved.getApprover().getId()).isEqualTo(defaultUser.userId());

        ArgumentCaptor<MapRecord<String, String, String>> record = ArgumentCaptor.captor();
        verify(stream).add(record.capture());
        assertThat(record.getValue().getStream()).isEqualTo(STREAM);
        assertThat(record.getValue().getValue()).isEqualTo(Map.of("documentId", id.toString()));
        assertThat(reload(id).getProcessingEnqueuedAt()).isNotNull();
    }

    @Test
    @DisplayName("an approval that fails is rolled back, and nothing reaches the stream")
    void failedApprovalPublishesNothing() {
        Long id = pendingWithFile().getId();

        assertThatThrownBy(() -> reviewService.approve(id, new ApproveDocumentRequest("novo nome", null, -1L)))
                .isInstanceOf(CategoryNotFoundException.class);

        Document untouched = reload(id);
        assertThat(untouched.getStatus()).isEqualTo(DocumentStatus.PENDING_APPROVAL);
        assertThat(untouched.getName()).isEqualTo("manual-de-fluxo.pdf");
        verifyNoInteractions(stream);
    }

    @Test
    @DisplayName("when Redis is down, the approval stands and the document waits, unsent, for the retry")
    @SuppressWarnings("unchecked")
    void redisDownLeavesTheDocumentForTheRetry() {
        Long id = pendingWithFile().getId();
        given(stream.add(any(MapRecord.class)))
                .willThrow(new RedisConnectionFailureException("Unable to connect to Redis"));

        reviewService.approve(id, ApproveDocumentRequest.NONE);

        Document approved = reload(id);
        assertThat(approved.getStatus()).isEqualTo(DocumentStatus.APPROVED);
        assertThat(approved.getProcessingStatus()).isEqualTo(ProcessingStatus.PROCESSING);
        assertThat(approved.getProcessingEnqueuedAt()).isNull();
    }

    @Test
    @DisplayName("a document approved but never sent -- the process died, or Redis was down -- is sent by the retry")
    @SuppressWarnings("unchecked")
    void retrySendsWhatTheApprovalNeverDid() {
        Long id = pendingWithFile().getId();
        // The approval commits and the send after it fails, which leaves the database exactly as a
        // process dying between the two would.
        given(stream.add(any(MapRecord.class)))
                .willThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
                .willReturn(null);
        reviewService.approve(id, ApproveDocumentRequest.NONE);

        // Redis is back. A cutoff in the future stands in for the grace period having passed.
        int sent = retry.retry(OffsetDateTime.now().plusMinutes(1));

        assertThat(sent).isGreaterThanOrEqualTo(1);
        assertThat(reload(id).getProcessingEnqueuedAt()).isNotNull();
        ArgumentCaptor<MapRecord<String, String, String>> record = ArgumentCaptor.captor();
        verify(stream, atLeast(2)).add(record.capture());
        assertThat(record.getAllValues()).extracting(MapRecord::getValue)
                .contains(Map.of("documentId", id.toString()));
    }

    @Test
    @DisplayName("a document already sent is not sent again by the retry")
    void retryLeavesWhatWasAlreadySent() {
        Long id = pendingWithFile().getId();
        reviewService.approve(id, ApproveDocumentRequest.NONE);
        clearInvocations(stream);

        retry.retry(OffsetDateTime.now().plusMinutes(1));

        ArgumentCaptor<MapRecord<String, String, String>> record = ArgumentCaptor.captor();
        verify(stream, atLeast(0)).add(record.capture());
        assertThat(record.getAllValues()).extracting(MapRecord::getValue)
                .doesNotContain(Map.of("documentId", id.toString()));
    }

    @Test
    @DisplayName("a rejection is committed with its reason, the file row is gone and the document row stays")
    void rejectionDeletesTheFileAndKeepsTheDocument() {
        Long id = pendingWithFile().getId();

        reviewService.reject(id, "Falta a secao de revisao A.");

        Document rejected = reload(id);
        assertThat(rejected.getStatus()).isEqualTo(DocumentStatus.REJECTED);
        assertThat(rejected.getReviewComment()).isEqualTo("Falta a secao de revisao A.");
        assertThat(rejected.getReviewedAt()).isNotNull();
        assertThat(documentFiles.findByDocumentId(id)).isEmpty();
        verifyNoInteractions(stream);
    }
}
