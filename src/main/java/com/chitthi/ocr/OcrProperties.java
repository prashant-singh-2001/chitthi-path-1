package com.chitthi.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Chitthi's own OCR pipeline tuning, separate from {@code sarvam.*}
 * ({@link com.chitthi.sarvam.SarvamProperties}), which holds limits Sarvam
 * itself imposes (chunk size, rate limits). This is everything Chitthi
 * decides on its own: worker concurrency, poll backoff, and how tolerant the
 * result parser is.
 */
@ConfigurationProperties(prefix = "chitthi.ocr")
public record OcrProperties(
        Worker worker,
        Poller poller,
        Poll poll,
        Dispatch dispatch,
        long maxPayloadBytes,
        Result result
) {

    /**
     * @param enabled lets a test disable the {@code @RabbitListener} so its
     *                context doesn't consume batches it has no Sarvam stub
     *                for; concurrency is deliberately not read from
     *                {@code sarvam.pipeline.concurrency}, which is the
     *                per-page translate/TTS cap, a different setting.
     */
    public record Worker(boolean enabled, int concurrency, int maxConcurrency) {
    }

    public record Poller(boolean enabled, Duration sweepInterval, int claimBatchSize, Duration claimLease) {
    }

    /** The backoff curve {@code OcrPollSchedule} implements: initialDelay * multiplier^(n-1), capped at maxDelay. */
    public record Poll(Duration initialDelay, double multiplier, Duration maxDelay, double jitterRatio, int maxAttempts) {
    }

    /** lease: how long a freshly-created batch waits before the redispatch sweep assumes its publish failed. */
    public record Dispatch(Duration lease, int maxSubmitAttempts) {
    }

    /** textFields is the ordered, configured guess at which JSON field in Sarvam's per-page output holds page text. */
    public record Result(long maxUnzippedBytes, List<String> textFields) {
    }
}
