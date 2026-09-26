package com.chitthi.pipeline.idempotency;

/**
 * Another worker holds a live lease on this idempotency key. Like
 * {@code SarvamRateLimitedException}'s siblings from the rate limiter and
 * circuit breaker, this means "wait, nothing went wrong with this message" -
 * callers should treat it as a pause, not a failure, and never burn a retry
 * attempt on it.
 */
public class StageTaskBusyException extends RuntimeException {

    public StageTaskBusyException(String idempotencyKey) {
        super("stage_task " + idempotencyKey + " is already being worked on");
    }
}
