package com.chitthi.usage;

import com.chitthi.usage.web.DocumentUsageView;
import com.chitthi.usage.web.EndpointUsageView;
import com.chitthi.usage.web.UsageSummaryEntry;
import com.chitthi.usage.web.UsageSummaryView;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Reads {@link ApiCallRepository}'s aggregate queries back as the two FR10 views. */
@Service
public class UsageQueryService {

    private final ApiCallRepository repository;

    public UsageQueryService(ApiCallRepository repository) {
        this.repository = repository;
    }

    public DocumentUsageView documentUsage(UUID documentId) {
        List<EndpointUsageRow> rows = repository.summarizeByDocument(documentId);
        List<EndpointUsageView> byEndpoint = rows.stream()
                .map(row -> new EndpointUsageView(row.getEndpoint(), row.getCalls(), row.getUnits(),
                        row.getCost(), row.getP50(), row.getP95()))
                .toList();
        BigDecimal total = rows.stream()
                .map(EndpointUsageRow::getCost)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new DocumentUsageView(documentId, total, byEndpoint);
    }

    public UsageSummaryView ownerUsage(String owner, OffsetDateTime from, OffsetDateTime to) {
        List<UsageSummaryRow> rows = repository.summarizeByOwnerAndDay(owner, from, to);
        List<UsageSummaryEntry> entries = rows.stream()
                .map(row -> new UsageSummaryEntry(row.getDay(), row.getDocumentId(), row.getTitle(),
                        row.getCalls(), row.getCost(), row.getP95()))
                .toList();
        return new UsageSummaryView(entries);
    }
}
