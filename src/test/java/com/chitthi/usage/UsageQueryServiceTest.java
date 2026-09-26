package com.chitthi.usage;

import com.chitthi.usage.web.DocumentUsageView;
import com.chitthi.usage.web.UsageSummaryView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageQueryServiceTest {

    private final ApiCallRepository repository = mock(ApiCallRepository.class);
    private final UsageQueryService service = new UsageQueryService(repository);

    @Test
    void documentUsage_sumsCostAcrossEndpoints() {
        UUID documentId = UUID.randomUUID();
        EndpointUsageRow translateRow = row("translate", 5L, 500L, new BigDecimal("1.0000"), 100.0, 150.0);
        EndpointUsageRow ttsRow = row("tts", 3L, 900L, new BigDecimal("2.7000"), 200.0, 300.0);
        when(repository.summarizeByDocument(documentId)).thenReturn(List.of(translateRow, ttsRow));

        DocumentUsageView view = service.documentUsage(documentId);

        assertThat(view.documentId()).isEqualTo(documentId);
        assertThat(view.totalCostInr()).isEqualByComparingTo("3.7000");
        assertThat(view.byEndpoint()).hasSize(2);
        assertThat(view.byEndpoint().get(0).endpoint()).isEqualTo("translate");
        assertThat(view.byEndpoint().get(0).p95Ms()).isEqualTo(150.0);
    }

    @Test
    void documentUsage_withNoCallsHasZeroTotalCost() {
        UUID documentId = UUID.randomUUID();
        when(repository.summarizeByDocument(documentId)).thenReturn(List.of());

        DocumentUsageView view = service.documentUsage(documentId);

        assertThat(view.totalCostInr()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(view.byEndpoint()).isEmpty();
    }

    @Test
    void ownerUsage_mapsEachRowToAnEntry() {
        UsageSummaryRow summaryRow = mock(UsageSummaryRow.class);
        UUID documentId = UUID.randomUUID();
        OffsetDateTime day = OffsetDateTime.now();
        when(summaryRow.getDay()).thenReturn(day);
        when(summaryRow.getDocumentId()).thenReturn(documentId);
        when(summaryRow.getTitle()).thenReturn("A letter");
        when(summaryRow.getCalls()).thenReturn(4L);
        when(summaryRow.getCost()).thenReturn(new BigDecimal("1.5"));
        when(summaryRow.getP95()).thenReturn(120.0);
        when(repository.summarizeByOwnerAndDay(any(), any(), any())).thenReturn(List.of(summaryRow));

        UsageSummaryView view = service.ownerUsage("owner", day.minusDays(30), day);

        assertThat(view.entries()).hasSize(1);
        assertThat(view.entries().get(0).title()).isEqualTo("A letter");
        assertThat(view.entries().get(0).documentId()).isEqualTo(documentId);
    }

    private EndpointUsageRow row(String endpoint, long calls, long units, BigDecimal cost, Double p50, Double p95) {
        EndpointUsageRow row = mock(EndpointUsageRow.class);
        when(row.getEndpoint()).thenReturn(endpoint);
        when(row.getCalls()).thenReturn(calls);
        when(row.getUnits()).thenReturn(units);
        when(row.getCost()).thenReturn(cost);
        when(row.getP50()).thenReturn(p50);
        when(row.getP95()).thenReturn(p95);
        return row;
    }
}
