package com.chitthi.sarvam;

/**
 * Sarvam rejected a call with HTTP 429. Never counted as a paid call - the
 * retry chain in {@link SarvamResilience} treats this as "wait, nothing went
 * wrong with the request" and retries it, unlike a real failure.
 */
public class SarvamRateLimitedException extends RuntimeException {

    private final long retryAfterMillis;

    public SarvamRateLimitedException(long retryAfterMillis) {
        super("Sarvam rate limited the request; retry after " + retryAfterMillis + "ms");
        this.retryAfterMillis = retryAfterMillis;
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}
