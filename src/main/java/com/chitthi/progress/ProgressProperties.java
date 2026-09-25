package com.chitthi.progress;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "chitthi.progress")
public record ProgressProperties(Duration emitterTimeout, long heartbeatIntervalMs) {
}
