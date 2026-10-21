package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.akpedia.server.config.ProcessingProperties;
import com.akpedia.server.repository.DocumentRepository;

/**
 * The retry of approved documents that never reached the stream: which ones it takes, and when
 * it stops.
 */
@ExtendWith(MockitoExtension.class)
class ProcessingRetryTest {

    private static final OffsetDateTime CUTOFF = OffsetDateTime.parse("2026-10-20T14:00:00Z");

    @Mock
    private DocumentRepository documents;
    @Mock
    private ProcessingQueue queue;

    private ProcessingRetry retry;

    @BeforeEach
    void setUp() {
        retry = new ProcessingRetry(documents, queue,
                new ProcessingProperties("documents:processing", "ml-workers", Duration.ofSeconds(30), Duration.ofMinutes(1), 2));
    }

    @Test
    @DisplayName("every pending document is sent again")
    void sendsEveryPendingDocument() {
        given(documents.findPendingEnqueue(eq(CUTOFF), any())).willReturn(List.of(7L, 8L));
        given(queue.publish(7L)).willReturn(true);
        given(queue.publish(8L)).willReturn(true);

        assertThat(retry.retry(CUTOFF)).isEqualTo(2);

        verify(queue).publish(7L);
        verify(queue).publish(8L);
    }

    @Test
    @DisplayName("the first refusal stops the run: Redis would refuse the rest too")
    void stopsAtTheFirstRefusal() {
        given(documents.findPendingEnqueue(eq(CUTOFF), any())).willReturn(List.of(7L, 8L));
        given(queue.publish(7L)).willReturn(false);

        assertThat(retry.retry(CUTOFF)).isZero();

        verify(queue, never()).publish(8L);
    }

    @Test
    @DisplayName("with nothing pending, nothing is sent")
    void nothingPending() {
        given(documents.findPendingEnqueue(eq(CUTOFF), any())).willReturn(List.of());

        assertThat(retry.retry(CUTOFF)).isZero();

        verifyNoInteractions(queue);
    }

    @Test
    @DisplayName("one run takes at most the configured batch")
    void takesAtMostOneBatch() {
        given(documents.findPendingEnqueue(eq(CUTOFF), any())).willReturn(List.of());

        retry.retry(CUTOFF);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.captor();
        verify(documents).findPendingEnqueue(eq(CUTOFF), page.capture());
        assertThat(page.getValue()).isEqualTo(PageRequest.of(0, 2));
    }

    @Test
    @DisplayName("a scheduled run leaves alone the documents approved within the grace period")
    void scheduledRunHonoursTheGrace() {
        given(documents.findPendingEnqueue(any(), any())).willReturn(List.of());

        retry.retryDue();

        // Those are normally being sent right after their commit; taking them too would duplicate them.
        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.captor();
        verify(documents).findPendingEnqueue(cutoff.capture(), any());
        assertThat(cutoff.getValue()).isCloseTo(OffsetDateTime.now().minusMinutes(1), within(5, ChronoUnit.SECONDS));
    }
}
