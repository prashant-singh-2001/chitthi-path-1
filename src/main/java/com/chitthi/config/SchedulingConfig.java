package com.chitthi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Kept as its own class, not on {@link com.chitthi.ChitthiApplication},
 * specifically so a test can exclude it and disable every {@code @Scheduled}
 * method (such as {@code OcrStatusPoller}) without touching the main
 * application configuration.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
