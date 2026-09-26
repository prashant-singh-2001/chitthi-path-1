package com.chitthi.usage.web;

import java.math.BigDecimal;

public record EndpointUsageView(String endpoint, long calls, long units, BigDecimal costInr,
                                 Double p50Ms, Double p95Ms) {
}
