package com.chitthi.usage;

import com.chitthi.pipeline.idempotency.StageTaskBusyException;
import com.chitthi.sarvam.SarvamRateLimitedException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Wraps exactly one real Sarvam HTTP call (never a {@code stage_task} or TTS
 * cache hit, since those never reach the network) and records it via
 * {@link UsageRecorder}, per FR10. Callers wrap the innermost supplier - the
 * one that actually invokes {@code SarvamClient} - not the whole
 * {@code stage_task}-guarded block, so a cached result is correctly recorded
 * as zero calls.
 */
@Component
public class UsageMeter {

    private static final Logger log = LoggerFactory.getLogger(UsageMeter.class);

    private final UsageRecorder recorder;

    public UsageMeter(UsageRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * @param endpoint one of {@link com.chitthi.sarvam.SarvamResilience}'s endpoint name constants
     */
    public <T> T meter(UUID documentId, String endpoint, int units, UnitType unitType, Supplier<T> call) {
        long startNanos = System.nanoTime();
        try {
            T result = call.get();
            safeRecord(documentId, endpoint, units, unitType, elapsedMs(startNanos), 200);
            return result;
        } catch (RequestNotPermitted | CallNotPermittedException | StageTaskBusyException e) {
            // A pause, not a call - the rate limiter, circuit breaker or a
            // busy stage_task turned this away before Sarvam was ever
            // reached. Recording it would misleadingly tag routine
            // throttling as an io_error outcome in the dashboard.
            throw e;
        } catch (RuntimeException e) {
            safeRecord(documentId, endpoint, units, unitType, elapsedMs(startNanos), statusOf(e));
            throw e;
        }
    }

    private void safeRecord(UUID documentId, String endpoint, int units, UnitType unitType, int latencyMs, int httpStatus) {
        try {
            recorder.record(documentId, endpoint, units, unitType, latencyMs, httpStatus);
        } catch (RuntimeException e) {
            // The ledger must never fail a paid call that already happened
            // (or already failed on its own terms) just because recording it
            // didn't work.
            log.warn("Failed to record usage for document {} endpoint {}", documentId, endpoint, e);
        }
    }

    private int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }

    /** {@code 0} (see {@link UsageRecorder#outcomeOf}, kept private there) means no real HTTP response was ever received. */
    private int statusOf(RuntimeException e) {
        if (e instanceof SarvamRateLimitedException) {
            return 429;
        }
        if (e instanceof RestClientResponseException responseException) {
            return responseException.getStatusCode().value();
        }
        return 0;
    }
}
