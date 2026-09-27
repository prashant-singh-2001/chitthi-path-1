package com.chitthi.cap;

import java.time.OffsetDateTime;

/**
 * FR15: the owner's daily word budget is spent. Carries enough for the
 * caller to build a 429 response with a reset time, the same way
 * {@link com.chitthi.sarvam.SarvamRateLimitedException} carries its
 * retry-after.
 */
public class DailyWordCapExceededException extends RuntimeException {

    private final OffsetDateTime resetAt;
    private final int usedWords;
    private final int limitWords;

    public DailyWordCapExceededException(OffsetDateTime resetAt, int usedWords, int limitWords) {
        super("Daily limit of %d words reached; resets at %s".formatted(limitWords, resetAt));
        this.resetAt = resetAt;
        this.usedWords = usedWords;
        this.limitWords = limitWords;
    }

    public OffsetDateTime resetAt() {
        return resetAt;
    }

    public int usedWords() {
        return usedWords;
    }

    public int limitWords() {
        return limitWords;
    }
}
