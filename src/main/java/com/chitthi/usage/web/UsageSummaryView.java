package com.chitthi.usage.web;

import java.util.List;

/** {@code GET /api/usage}: spend and latency per document per day for one owner. */
public record UsageSummaryView(List<UsageSummaryEntry> entries) {
}
