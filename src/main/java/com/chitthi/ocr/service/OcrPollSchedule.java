package com.chitthi.ocr.service;

import com.chitthi.ocr.OcrProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Random;

/**
 * The backoff curve a batch's status poll follows: {@code initialDelay *
 * multiplier^(pollCount-1)}, capped at {@code maxDelay}, with jitter. No
 * clock field and no database access - a pure function of {@code pollCount}
 * plus this instance's random source, so the curve itself tests without
 * mocking time.
 *
 * <p>Jitter exists because a multi-chunk document creates several batches
 * that submit within milliseconds of each other; without it, their polls
 * would stay in lockstep on every retry forever instead of spreading out.
 */
@Component
public class OcrPollSchedule {

    private final OcrProperties.Poll pollProperties;
    private final Random random;

    // Explicit @Autowired is required, not decoration: with two declared
    // constructors (this one, plus the package-private one below for
    // deterministic jitter tests), Spring's implicit single-constructor
    // autowiring rule doesn't apply, and it otherwise falls back to a
    // no-arg constructor that doesn't exist here.
    @Autowired
    public OcrPollSchedule(OcrProperties ocrProperties) {
        this(ocrProperties, new Random());
    }

    OcrPollSchedule(OcrProperties ocrProperties, Random random) {
        this.pollProperties = ocrProperties.poll();
        this.random = random;
    }

    public Duration delayAfter(int pollCount) {
        double exponent = Math.max(0, pollCount - 1);
        double raw = pollProperties.initialDelay().toMillis() * Math.pow(pollProperties.multiplier(), exponent);
        double capped = Math.min(raw, pollProperties.maxDelay().toMillis());
        double jitterFactor = 1 + (random.nextDouble() * 2 - 1) * pollProperties.jitterRatio();
        long millis = Math.round(capped * jitterFactor);
        return Duration.ofMillis(Math.max(0, millis));
    }

    public boolean hasExceededMaxAttempts(int pollCount) {
        return pollCount >= pollProperties.maxAttempts();
    }
}
