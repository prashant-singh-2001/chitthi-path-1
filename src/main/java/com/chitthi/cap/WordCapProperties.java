package com.chitthi.cap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chitthi.cap")
public record WordCapProperties(int dailyWords, String zone) {
}
