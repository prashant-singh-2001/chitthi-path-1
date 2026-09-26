package com.chitthi.usage;

import com.chitthi.pipeline.idempotency.StageTaskBusyException;
import com.chitthi.sarvam.SarvamRateLimitedException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class UsageMeterTest {

    private final UsageRecorder recorder = mock(UsageRecorder.class);
    private final UsageMeter meter = new UsageMeter(recorder);

    @Test
    void aSuccessfulCallRecordsStatus200AndReturnsTheResult() {
        UUID documentId = UUID.randomUUID();

        String result = meter.meter(documentId, "translate", 42, UnitType.CHARACTERS, () -> "translated");

        assertThat(result).isEqualTo("translated");
        verify(recorder).record(eq(documentId), eq("translate"), eq(42), eq(UnitType.CHARACTERS), anyInt(), eq(200));
    }

    @Test
    void aRestClientErrorRecordsItsStatusCodeAndRethrows() {
        UUID documentId = UUID.randomUUID();
        HttpServerErrorException boom = HttpServerErrorException.create(
                HttpStatusCode.valueOf(500), "Server Error", null, null, null);

        assertThatThrownBy(() -> meter.meter(documentId, "tts", 10, UnitType.CHARACTERS, () -> {
            throw boom;
        })).isSameAs(boom);

        verify(recorder).record(eq(documentId), eq("tts"), eq(10), eq(UnitType.CHARACTERS), anyInt(), eq(500));
    }

    @Test
    void aRateLimitedExceptionRecordsStatus429() {
        UUID documentId = UUID.randomUUID();
        SarvamRateLimitedException boom = new SarvamRateLimitedException(1000);

        assertThatThrownBy(() -> meter.meter(documentId, "vision-submit", 1, UnitType.PAGES, () -> {
            throw boom;
        })).isSameAs(boom);

        verify(recorder).record(eq(documentId), eq("vision-submit"), eq(1), eq(UnitType.PAGES), anyInt(), eq(429));
    }

    @Test
    void anUnrecognizedFailureRecordsStatusZero() {
        UUID documentId = UUID.randomUUID();
        RuntimeException boom = new RuntimeException("connection reset");

        assertThatThrownBy(() -> meter.meter(documentId, "tts", 10, UnitType.CHARACTERS, () -> {
            throw boom;
        })).isSameAs(boom);

        verify(recorder).record(eq(documentId), eq("tts"), eq(10), eq(UnitType.CHARACTERS), anyInt(), eq(0));
    }

    @Test
    void aPauseIsNeverRecorded() {
        UUID documentId = UUID.randomUUID();
        RateLimiter limiter = RateLimiter.of("test", RateLimiterConfig.custom()
                .limitForPeriod(1).limitRefreshPeriod(Duration.ofMinutes(1)).timeoutDuration(Duration.ZERO).build());
        limiter.acquirePermission();
        RequestNotPermitted pause = captureRequestNotPermitted(limiter);

        assertThatThrownBy(() -> meter.meter(documentId, "tts", 10, UnitType.CHARACTERS, () -> {
            throw pause;
        })).isSameAs(pause);

        CircuitBreaker breaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(1).minimumNumberOfCalls(1).failureRateThreshold(1f)
                .waitDurationInOpenState(Duration.ofMinutes(5)).build());
        breaker.transitionToOpenState();
        CallNotPermittedException breakerOpen = CallNotPermittedException.createCallNotPermittedException(breaker);
        assertThatThrownBy(() -> meter.meter(documentId, "tts", 10, UnitType.CHARACTERS, () -> {
            throw breakerOpen;
        })).isSameAs(breakerOpen);

        StageTaskBusyException busy = new StageTaskBusyException("some-key");
        assertThatThrownBy(() -> meter.meter(documentId, "tts", 10, UnitType.CHARACTERS, () -> {
            throw busy;
        })).isSameAs(busy);

        verify(recorder, never()).record(any(), anyString(), anyInt(), any(), anyInt(), anyInt());
    }

    @Test
    void aRecorderFailureIsSwallowedAndTheResultStillReturns() {
        UUID documentId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(recorder).record(any(), anyString(), anyInt(), any(), anyInt(), anyInt());

        String result = meter.meter(documentId, "translate", 5, UnitType.CHARACTERS, () -> "ok");

        assertThat(result).isEqualTo("ok");
    }

    private RequestNotPermitted captureRequestNotPermitted(RateLimiter limiter) {
        try {
            RateLimiter.decorateSupplier(limiter, () -> "x").get();
        } catch (RequestNotPermitted e) {
            return e;
        }
        throw new AssertionError("Expected RequestNotPermitted");
    }
}
