package com.chitthi.audio;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "chitthi.audio")
public record AudioProperties(Duration urlTtl) {
}
