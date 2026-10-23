package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.akpedia.server.config.ProcessingProperties;
import com.akpedia.server.repository.DocumentRepository;

/**
 * When an approved document reaches the Redis Stream -- after its transaction commits, never
 * before, and not at all if it rolls back -- and when the send is recorded on the document.
 *
 * <p>Runs on Spring's real transaction synchronization -- which is what decides when an
 * {@code AFTER_COMMIT} listener fires -- over a transaction manager that needs no database. The
 * approval service is not involved: it only publishes the event, and its own test covers that.
 */
@SpringJUnitConfig(ProcessingQueueTest.Config.class)
class ProcessingQueueTest {

    private static final String STREAM = "documents:processing";

    @Configuration
    @EnableTransactionManagement
    @Import(ProcessingQueue.class)
    static class Config {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoDatabaseTransactionManager();
        }

        @Bean
        ProcessingProperties processingProperties() {
            return new ProcessingProperties(STREAM, "ml-workers", Duration.ofSeconds(30), Duration.ofMinutes(1), 50);
        }
    }

    /**
     * Begins, commits and rolls back nothing, so only Spring's synchronization is left to test.
     * Can be told to fail the next commit, the way a database refusing it at the last step would.
     */
    static class NoDatabaseTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        private boolean failNextCommit;

        void failNextCommit() {
            this.failNextCommit = true;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failNextCommit) {
                failNextCommit = false;
                throw new TransactionSystemException("o banco recusou o commit");
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessingQueue queue;

    @MockBean
    private StringRedisTemplate redis;

    @MockBean
    private DocumentRepository documents;

    private StreamOperations<String, Object, Object> stream;
    private TransactionTemplate transaction;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stream = mock(StreamOperations.class);
        given(redis.opsForStream()).willReturn(stream);
        transaction = new TransactionTemplate(transactionManager);
    }

    @SuppressWarnings("unchecked")
    private void givenRedisIsDown() {
        given(stream.add(any(MapRecord.class)))
                .willThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
    }

    // ------------------------------------------------------------------
    // when it is sent
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the document id is added to the stream once the transaction commits, not before")
    void publishesAfterCommit() {
        transaction.executeWithoutResult(status -> {
            events.publishEvent(new DocumentApprovedEvent(7L));

            // Still inside the transaction: nothing may have reached Redis yet.
            verifyNoInteractions(redis);
        });

        ArgumentCaptor<MapRecord<String, String, String>> record = ArgumentCaptor.captor();
        verify(stream).add(record.capture());
        assertThat(record.getValue().getStream()).isEqualTo(STREAM);
        assertThat(record.getValue().getValue()).isEqualTo(Map.of(ProcessingQueue.DOCUMENT_ID_FIELD, "7"));
    }

    @Test
    @DisplayName("a transaction that rolls back adds nothing to the stream")
    void nothingIsPublishedOnRollback() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            events.publishEvent(new DocumentApprovedEvent(7L));
            throw new IllegalStateException("a aprovacao falhou depois do evento");
        })).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(redis, documents);
    }

    @Test
    @DisplayName("a commit that fails at the last step adds nothing to the stream")
    void nothingIsPublishedWhenTheCommitItselfFails() {
        // What tells AFTER_COMMIT apart from BEFORE_COMMIT: the latter would already have sent the
        // id by the time the database refused the commit.
        ((NoDatabaseTransactionManager) transactionManager).failNextCommit();

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                status -> events.publishEvent(new DocumentApprovedEvent(7L))))
                .isInstanceOf(TransactionSystemException.class);

        verifyNoInteractions(redis, documents);
    }

    @Test
    @DisplayName("a transaction marked rollback-only adds nothing to the stream either")
    void nothingIsPublishedWhenMarkedRollbackOnly() {
        transaction.executeWithoutResult(status -> {
            events.publishEvent(new DocumentApprovedEvent(7L));
            status.setRollbackOnly();
        });

        verifyNoInteractions(redis, documents);
    }

    @Test
    @DisplayName("an event published outside any transaction is not acted on")
    void outsideATransactionNothingIsPublished() {
        // There is no commit to wait for, so the listener never fires; approve() always runs
        // inside one, so this would only happen if that were ever removed.
        events.publishEvent(new DocumentApprovedEvent(7L));

        verifyNoInteractions(redis, documents);
    }

    // ------------------------------------------------------------------
    // recording the send
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the send is recorded on the document only after Redis accepted the id")
    @SuppressWarnings("unchecked")
    void sendIsRecordedAfterRedisAccepted() {
        // Checked at the moment Redis is called: recorded any earlier, a send that then failed
        // would leave the document marked as enqueued, and the retry would never find it.
        given(stream.add(any(MapRecord.class))).willAnswer(invocation -> {
            verify(documents, never()).markEnqueued(any(), any());
            return null;
        });

        transaction.executeWithoutResult(status -> events.publishEvent(new DocumentApprovedEvent(7L)));

        InOrder order = inOrder(stream, documents);
        order.verify(stream).add(any(MapRecord.class));
        order.verify(documents).markEnqueued(eq(7L), any());
    }

    @Test
    @DisplayName("when Redis refuses the id, nothing is recorded, so the retry finds the document")
    void redisDownLeavesTheDocumentForTheRetry() {
        givenRedisIsDown();

        // The approval committed: the failure must not surface as if it had not.
        transaction.executeWithoutResult(status -> events.publishEvent(new DocumentApprovedEvent(7L)));

        verify(documents, never()).markEnqueued(any(), any());
        assertThat(queue.publish(7L)).isFalse();
    }

    @Test
    @DisplayName("when recording the send fails, the id was still sent, and nothing surfaces as an error")
    void failureToRecordDoesNotSurface() {
        willThrow(new DataAccessResourceFailureException("banco fora do ar"))
                .given(documents).markEnqueued(any(), any());

        // Sent but not recorded: the retry sends it once more, which the worker has to tolerate.
        assertThat(queue.publish(7L)).isTrue();
        transaction.executeWithoutResult(status -> events.publishEvent(new DocumentApprovedEvent(8L)));
    }
}
