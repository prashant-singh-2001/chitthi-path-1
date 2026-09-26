package com.chitthi.usage.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record DocumentUsageView(UUID documentId, BigDecimal totalCostInr, List<EndpointUsageView> byEndpoint) {
}
