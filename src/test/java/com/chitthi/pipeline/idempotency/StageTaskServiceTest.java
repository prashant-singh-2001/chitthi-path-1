package com.chitthi.pipeline.idempotency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StageTaskServiceTest {

    private final StageTaskRepository repository = mock(StageTaskRepository.class);
    private final StageTaskService service = new StageTaskService(repository, Duration.ofSeconds(90));

    @Test
    void aFreshKeyClaimsTheRowAndCallsThenMarksItDone() {
        UUID pageId = UUID.randomUUID();
        when(repository.insertIfAbsent(eq(pageId), eq("TRANSLATE"), eq("key-1"), any())).thenReturn(1);

        String result = service.callOnce("key-1", pageId, "TRANSLATE", () -> "translated text");

        assertThat(result).isEqualTo("translated text");
        verify(repository).markDone("key-1", "translated text");
        verify(repository, never()).findByIdempotencyKey(anyString());
    }

    @Test
    void aKeyAlreadyDoneReturnsTheStoredResultWithNoCall() {
        UUID pageId = UUID.randomUUID();
        when(repository.insertIfAbsent(eq(pageId), eq("TRANSLATE"), eq("key-1"), any())).thenReturn(0);
        StageTask done = mockTask(StageTaskStatus.DONE, "cached result");
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(done));

        AtomicInteger calls = new AtomicInteger();
        String result = service.callOnce("key-1", pageId, "TRANSLATE", () -> {
            calls.incrementAndGet();
            return "should not be called";
        });

        assertThat(result).isEqualTo("cached result");
        assertThat(calls.get()).isZero();
        verify(repository, never()).takeOverIfAvailable(anyString(), any(), any());
    }

    @Test
    void aKeyRunningWithALiveLeaseThrowsBusyWithoutCalling() {
        UUID pageId = UUID.randomUUID();
        when(repository.insertIfAbsent(eq(pageId), eq("TRANSLATE"), eq("key-1"), any())).thenReturn(0);
        StageTask running = mockTask(StageTaskStatus.RUNNING, null);
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(running));
        when(repository.takeOverIfAvailable(eq("key-1"), any(), any())).thenReturn(0);

        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> service.callOnce("key-1", pageId, "TRANSLATE", () -> {
            calls.incrementAndGet();
            return "x";
        })).isInstanceOf(StageTaskBusyException.class);

        assertThat(calls.get()).isZero();
    }

    @Test
    void aPendingKeyIsTakenOverAndCalled() {
        UUID pageId = UUID.randomUUID();
        when(repository.insertIfAbsent(eq(pageId), eq("TRANSLATE"), eq("key-1"), any())).thenReturn(0);
        StageTask pending = mockTask(StageTaskStatus.PENDING, null);
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(pending));
        when(repository.takeOverIfAvailable(eq("key-1"), any(), any())).thenReturn(1);

        String result = service.callOnce("key-1", pageId, "TRANSLATE", () -> "retried result");

        assertThat(result).isEqualTo("retried result");
        verify(repository).markDone("key-1", "retried result");
    }

    @Test
    void aFailedCallLeavesTheKeyPendingAndRethrows() {
        UUID pageId = UUID.randomUUID();
        when(repository.insertIfAbsent(eq(pageId), eq("TRANSLATE"), eq("key-1"), any())).thenReturn(1);
        RuntimeException boom = new RuntimeException("Sarvam is down");

        assertThatThrownBy(() -> service.callOnce("key-1", pageId, "TRANSLATE", () -> {
            throw boom;
        })).isSameAs(boom);

        verify(repository).markFailed("key-1", "Sarvam is down");
        verify(repository, never()).markDone(anyString(), anyString());
    }

    private StageTask mockTask(StageTaskStatus status, String result) {
        StageTask task = mock(StageTask.class);
        when(task.getStatus()).thenReturn(status);
        when(task.getResult()).thenReturn(result);
        when(task.getLeaseUntil()).thenReturn(OffsetDateTime.now().minusMinutes(1));
        return task;
    }
}
