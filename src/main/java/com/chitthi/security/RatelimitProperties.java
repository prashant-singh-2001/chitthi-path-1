package com.chitthi.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chitthi.ratelimit")
public record RatelimitProperties(int perIpRequestsPerMinute) {

    public boolean enabled() {
        return perIpRequestsPerMinute > 0;
    }
}
