package com.chitthi.sarvam;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * No Mockito - plain objects over the real Resilience4j config, so unlike
 * most of this project's unit tests this runs on this machine too. Tests the
 * breaker's config directly rather than through {@link SarvamResilience}'s
 * whole chain: a rate-limiter rejection only arrives after the limiter's
 * full timeout (6s for Vision), so driving enough of them through the real
 * chain would take minutes.
 */
class SarvamResilienceTest {

    private static RequestNotPermitted aLimiterRejection() {
        return RequestNotPermitted.createRequestNotPermitted(
                RateLimiter.of("vision-submit", RateLimiterConfig.ofDefaults()));
    }

    private static void fail(CircuitBreaker breaker, RuntimeException failure) {
        catchThrowable(() -> breaker.executeSupplier(() -> {
            throw failure;
        }));
    }

    /**
     * Day 12's load test found that 93-95% of the "paused" submits were
     * circuit-breaker rejections, not rate-limiter ones. The breaker wraps
     * the limiter, so a {@link RequestNotPermitted} - the limiter turning a
     * caller away before Sarvam was ever called - was recorded as a failure
     * of the endpoint. Enough of them opened the breaker for 30s and blocked
     * every submit, with permits going unused the whole time.
     */
    @Test
    void limiterRejectionsNeverOpenTheCircuitBreaker() {
        CircuitBreaker breaker = CircuitBreaker.of("vision-submit", SarvamResilience.circuitBreakerConfig());

        // Well past minimumNumberOfCalls (5) and the 10-call window.
        for (int i = 0; i < 30; i++) {
            fail(breaker, aLimiterRejection());
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void realFailuresStillOpenTheCircuitBreaker() {
        CircuitBreaker breaker = CircuitBreaker.of("vision-submit", SarvamResilience.circuitBreakerConfig());

        for (int i = 0; i < 10; i++) {
            fail(breaker, new IllegalStateException("Sarvam answered 500"));
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
