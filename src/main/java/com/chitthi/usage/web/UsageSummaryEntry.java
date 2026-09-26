package com.chitthi.usage.web;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record UsageSummaryEntry(OffsetDateTime day, UUID documentId, String title, long calls,
                                 BigDecimal costInr, Double p95Ms) {
}
