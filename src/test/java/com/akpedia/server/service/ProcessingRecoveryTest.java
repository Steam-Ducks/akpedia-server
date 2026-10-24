package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.akpedia.server.config.ProcessingProperties;
import com.akpedia.server.dto.ProcessingFailureRequest;
import com.akpedia.server.exception.DocumentNotFoundException;

/**
 * Which stuck pending messages the recovery job takes over: which are left alone (not idle
 * enough), which are redelivered, and which are given up on past the delivery limit.
 */
@ExtendWith(MockitoExtension.class)
class ProcessingRecoveryTest {

    private static final String STREAM = "documents:processing";
    private static final String GROUP = "ml-workers";
    private static final String MESSAGE_ID = "1761234567890-0";

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ProcessingQueue queue;
    @Mock
    private DocumentIndexingService indexingService;
    private StreamOperations<String, String, String> stream;

    private ProcessingRecovery recovery;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stream = mock(StreamOperations.class);
        given(redis.<String, String>opsForStream()).willReturn(stream);
        recovery = new ProcessingRecovery(redis, queue, indexingService,
                new ProcessingProperties(STREAM, GROUP, Duration.ofSeconds(30), Duration.ofMinutes(1), 50,
                        Duration.ofMinutes(1), Duration.ofMinutes(10), 50, 3));
    }

    private static PendingMessage pending(String messageId, Duration idle, long deliveryCount) {
        return new PendingMessage(RecordId.of(messageId), Consumer.from(GROUP, "worker-1"), idle, deliveryCount);
    }

    private static MapRecord<String, String, String> recordOf(String messageId, Long documentId) {
        return MapRecord.<String, String, String>create(STREAM,
                documentId == null ? Map.of() : Map.of("documentId", documentId.toString()))
                .withId(RecordId.of(messageId));
    }

    @Test
    @DisplayName("with nothing pending, nothing is touched")
    void nothingPending() {
        given(stream.pending(eq(STREAM), eq(GROUP), any(), eq(50L)))
                .willReturn(new PendingMessages(GROUP, List.of()));

        assertThat(recovery.recover()).isZero();
        verifyNoInteractions(queue, indexingService);
    }

    @Test
    @DisplayName("a message not yet idle past the threshold is left alone")
    void leavesAMessageWithinTheIdleThreshold() {
        given(stream.pending(eq(STREAM), eq(GROUP), any(), eq(50L)))
                .willReturn(new PendingMessages(GROUP, List.of(pending(MESSAGE_ID, Duration.ofMinutes(5), 1))));

        assertThat(recovery.recover()).isZero();
        verifyNoInteractions(queue, indexingService);
    }

    @Test
    @DisplayName("an idle message under the delivery limit is claimed, republished and acknowledged")
    void redeliversAnIdleMessageUnderTheLimit() {
        given(stream.pending(eq(STREAM), eq(GROUP), any(), eq(50L)))
                .willReturn(new PendingMessages(GROUP, List.of(pending(MESSAGE_ID, Duration.ofMinutes(15), 1))));
        given(stream.claim(eq(STREAM), eq(GROUP), eq(ProcessingRecovery.RECOVERY_CONSUMER), any(XClaimOptions.class)))
                .willReturn(List.of(recordOf(MESSAGE_ID, 7L)));

        assertThat(recovery.recover()).isEqualTo(1);

        verify(queue).publish(7L);
        verify(queue).acknowledge(MESSAGE_ID);
        verifyNoInteractions(indexingService);
    }

    @Test
    @DisplayName("a message delivered the maximum number of times is not claimed")
    void doesNotClaimAMessageAtTheDeliveryLimit() {
        given(stream.pending(eq(STREAM), eq(GROUP), any(), eq(50L)))
                .willReturn(new PendingMessages(GROUP, List.of(pending(MESSAGE_ID, Duration.ofMinutes(15), 3))));
        given(stream.range(eq(STREAM), any())).willReturn(List.of(recordOf(MESSAGE_ID, 7L)));

        recovery.recover();

        verify(stream, never()).claim(any(), any(), any(), any(XClaimOptions.class));
        verify(queue, never()).publish(any());
    }

    @Test
    @DisplayName("a message past the delivery limit marks its document FAILED, with the retry-limit reason")
    void givesUpOnAMessagePastTheDeliveryLimit() {
        given(stream.pending(eq(STREAM), eq(GROUP), any(), eq(50L)))
                .willReturn(new PendingMessages(GROUP, List.of(pending(MESSAGE_ID, Duration.ofMinutes(15), 3))));
        given(stream.range(eq(STREAM), any())).willReturn(List.of(recordOf(MESSAGE_ID, 7L)));

        assertThat(recovery.recover()).isEqualTo(1);

        ArgumentCaptor<ProcessingFailureRequest> failure = ArgumentCaptor.captor();
        verify(indexingService).recordFailure(eq(7L), failure.capture());
        assertThat(failure.getValue().messageId()).isEqualTo(MESSAGE_ID);
        assertThat(failure.getValue().code()).isEqualTo("retry_limit_exceeded");
        verifyNoInteractions(queue);
    }

    @Test
    @DisplayName("giving up on a message whose document no longer exists does not fail the run")
    void givingUpOnAMissingDocumentDoesNotFail() {
        given(stream.pending(eq(STREAM), eq(GROUP), any(), eq(50L)))
                .willReturn(new PendingMessages(GROUP, List.of(pending(MESSAGE_ID, Duration.ofMinutes(15), 3))));
        given(stream.range(eq(STREAM), any())).willReturn(List.of(recordOf(MESSAGE_ID, 7L)));
        given(indexingService.recordFailure(eq(7L), any())).willThrow(new DocumentNotFoundException(7L));

        assertThat(recovery.recover()).isEqualTo(1);
    }

    @Test
    @DisplayName("a claim that comes back empty -- claimed or acknowledged by something else first -- publishes nothing")
    void aRaceThatLosesTheClaimPublishesNothing() {
        given(stream.pending(eq(STREAM), eq(GROUP), any(), eq(50L)))
                .willReturn(new PendingMessages(GROUP, List.of(pending(MESSAGE_ID, Duration.ofMinutes(15), 1))));
        given(stream.claim(eq(STREAM), eq(GROUP), eq(ProcessingRecovery.RECOVERY_CONSUMER), any(XClaimOptions.class)))
                .willReturn(List.of());

        recovery.recover();

        verifyNoInteractions(queue);
    }

    @Test
    @DisplayName("a claimed message whose stream entry carries no documentId is only acknowledged")
    void aClaimedMessageWithNoDocumentIdIsOnlyAcknowledged() {
        given(stream.pending(eq(STREAM), eq(GROUP), any(), eq(50L)))
                .willReturn(new PendingMessages(GROUP, List.of(pending(MESSAGE_ID, Duration.ofMinutes(15), 1))));
        given(stream.claim(eq(STREAM), eq(GROUP), eq(ProcessingRecovery.RECOVERY_CONSUMER), any(XClaimOptions.class)))
                .willReturn(List.of(recordOf(MESSAGE_ID, null)));

        recovery.recover();

        verify(queue, never()).publish(any());
        verify(queue).acknowledge(MESSAGE_ID);
    }

    @Test
    @DisplayName("several idle messages are all handled in one pass")
    void handlesEveryIdleMessageInOnePass() {
        given(stream.pending(eq(STREAM), eq(GROUP), any(), eq(50L))).willReturn(new PendingMessages(GROUP, List.of(
                pending("1-0", Duration.ofMinutes(15), 1),
                pending("2-0", Duration.ofMinutes(15), 1))));
        given(stream.claim(eq(STREAM), eq(GROUP), eq(ProcessingRecovery.RECOVERY_CONSUMER), any(XClaimOptions.class)))
                .willReturn(List.of(recordOf("1-0", 1L)))
                .willReturn(List.of(recordOf("2-0", 2L)));

        assertThat(recovery.recover()).isEqualTo(2);

        verify(queue).publish(1L);
        verify(queue).publish(2L);
    }

    @Test
    @DisplayName("when Redis cannot be read, the run gives up quietly and tries again next time")
    void redisFailureIsToleratedSilently() {
        given(stream.pending(eq(STREAM), eq(GROUP), any(), eq(50L)))
                .willThrow(new DataAccessResourceFailureException("banco fora do ar"));

        assertThat(recovery.recover()).isZero();
        verifyNoInteractions(queue, indexingService);
    }

    @Test
    @DisplayName("the batch size configured is what is asked of XPENDING")
    void usesTheConfiguredBatchSize() {
        given(stream.pending(eq(STREAM), eq(GROUP), eq(Range.unbounded()), eq(50L)))
                .willReturn(new PendingMessages(GROUP, List.of()));

        recovery.recover();

        verify(stream).pending(STREAM, GROUP, Range.unbounded(), 50L);
    }
}
