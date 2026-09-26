package com.chitthi.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * {@code delays.size()} is how many redeliveries a message gets before
 * {@link PipelineMessageRecoverer} gives up and dead-letters it - one more
 * than that (the first, un-retried attempt) is the total attempt count FR7
 * asks for.
 */
@ConfigurationProperties(prefix = "chitthi.retry")
public record RetryProperties(List<Duration> delays) {
}
