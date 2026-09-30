package com.chitthi.sarvam;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One Resilience4j chain per Sarvam endpoint, built once from
 * {@link SarvamProperties} and reused for every call to that endpoint - "all
 * workers share one budget" per the requirements doc's rate-limiting note.
 *
 * <p>Execution order, outside in: a {@link Retry} that only retries a 429
 * (never a real failure) using Sarvam's own {@code Retry-After}, guarded by a
 * {@link CircuitBreaker} that opens on repeated 5xx/IO failures (never on a
 * 429 or another 4xx - those aren't the endpoint being unhealthy), guarded by
 * a {@link RateLimiter} shaped as a continuously-replenishing token bucket
 * rather than a once-a-minute reset, matching how Sarvam's own limits work.
 *
 * <p>In memory, single instance - see {@code SseEmitterRegistry}'s
 * TODO(scale) for the same shape of gap; several instances would need a
 * shared/distributed limiter.
 */
@Component
public class SarvamResilience {

    public static final String VISION_SUBMIT = "vision-submit";
    public static final String TRANSLATE = "translate";
    public static final String TTS = "tts";

    private final Map<String, Chain> chains;

    public SarvamResilience(SarvamProperties properties) {
        this.chains = Map.of(
                VISION_SUBMIT, buildChain(VISION_SUBMIT, properties.rateLimits().visionRequestsPerMinute()),
                TRANSLATE, buildChain(TRANSLATE, properties.rateLimits().translateRequestsPerMinute()),
                TTS, buildChain(TTS, properties.rateLimits().ttsRequestsPerMinute()));
    }

    /**
     * Runs {@code call} through the named endpoint's chain. Throws
     * {@code io.github.resilience4j.ratelimiter.RequestNotPermitted} or
     * {@code io.github.resilience4j.circuitbreaker.CallNotPermittedException}
     * when the caller should treat this as "wait, nothing went wrong with
     * this message" rather than a real failure.
     */
    public <T> T execute(String endpoint, Supplier<T> call) {
        Chain chain = chains.get(endpoint);
        if (chain == null) {
            throw new IllegalArgumentException("No resilience chain configured for endpoint: " + endpoint);
        }
        Supplier<T> decorated = RateLimiter.decorateSupplier(chain.rateLimiter(), call);
        decorated = CircuitBreaker.decorateSupplier(chain.circuitBreaker(), decorated);
        decorated = Retry.decorateSupplier(chain.retry(), decorated);
        return decorated.get();
    }

    private Chain buildChain(String name, int requestsPerMinute) {
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                // A continuously-replenishing bucket (one permit trickling in
                // every 60s/rpm) rather than "rpm permits, reset every
                // minute" - closer to how Sarvam's own limits behave, and it
                // avoids every worker bursting at the top of each minute.
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMillis(Math.max(1, 60_000L / requestsPerMinute)))
                // A caller that can't get a permit in time is not rejected
                // at once - it waits this full timeout first, then fails.
                // Waiting out a whole refresh period instead (6s for Vision)
                // was measured in Day 12's load test with the circuit
                // breaker fixed: fewer rejections (22 against 79 for 20
                // documents) but the same drain time (119.0s against 120.2s,
                // both at the ~120s floor the limiter allows), while holding
                // a consumer twice as long per futile wait. Not worth it.
                .timeoutDuration(Duration.ofSeconds(3))
                .build();

        CircuitBreakerConfig circuitBreakerConfig = circuitBreakerConfig();

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(4)
                .retryOnException(e -> e instanceof SarvamRateLimitedException)
                .intervalBiFunction((attempt, either) -> {
                    if (either.isLeft() && either.getLeft() instanceof SarvamRateLimitedException rateLimited) {
                        return Math.min(rateLimited.retryAfterMillis(), 30_000L);
                    }
                    return 1000L;
                })
                .build();

        return new Chain(
                RateLimiter.of(name, rateLimiterConfig),
                CircuitBreaker.of(name, circuitBreakerConfig),
                Retry.of(name, retryConfig));
    }

    /** Package-private so the breaker's behaviour can be tested without waiting out real rate-limiter timeouts. */
    static CircuitBreakerConfig circuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .minimumNumberOfCalls(5)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                // A 429 is Sarvam telling us to slow down, not a failure; any
                // other 4xx is a bad request on our side, not the endpoint
                // being unhealthy. Only 5xx/IO/timeout failures count.
                .recordException(e -> !(e instanceof SarvamRateLimitedException) && !(e instanceof HttpClientErrorException))
                // The breaker wraps the limiter (see execute), so the
                // limiter's own rejection passes through it. That call never
                // reached Sarvam, so it says nothing about the endpoint's
                // health - and unlike the exclusions above, which count as
                // successes (Sarvam did answer), it must count as neither.
                // Recorded as a failure, a few of these opened the breaker
                // for 30s and blocked every submit with permits going
                // unused: Day 12's load test saw 93-96% of all "paused"
                // submits turn out to be breaker rejections, not limiter ones.
                .ignoreExceptions(RequestNotPermitted.class)
                .build();
    }

    private record Chain(RateLimiter rateLimiter, CircuitBreaker circuitBreaker, Retry retry) {
    }
}
