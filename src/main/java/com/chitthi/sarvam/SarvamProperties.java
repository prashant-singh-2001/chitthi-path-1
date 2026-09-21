package com.chitthi.sarvam;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sarvam")
public record SarvamProperties(
        String baseUrl,
        String apiSubscriptionKey,
        RateLimits rateLimits,
        Pipeline pipeline
) {
    public record RateLimits(int visionRequestsPerMinute) {
    }

    public record Pipeline(
            int ocrMaxPagesPerChunk,
            int translateMaxCharsPerRequest,
            int ttsMaxCharsPerRequest,
            int concurrency
    ) {
    }
}
