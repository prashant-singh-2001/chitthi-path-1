package com.chitthi.sarvam;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "sarvam")
public record SarvamProperties(
        String baseUrl,
        String apiSubscriptionKey,
        Http http,
        RateLimits rateLimits,
        Pipeline pipeline,
        Translate translate,
        Tts tts
) {
    public record Http(Duration connectTimeout, Duration readTimeout) {
    }

    public record RateLimits(int visionRequestsPerMinute, int translateRequestsPerMinute, int ttsRequestsPerMinute) {
    }

    public record Pipeline(
            int ocrMaxPagesPerChunk,
            int translateMaxCharsPerRequest,
            int ttsMaxCharsPerRequest,
            int concurrency
    ) {
    }

    public record Translate(String model) {
    }

    public record Tts(String model, String speaker, int sampleRate) {
    }
}
