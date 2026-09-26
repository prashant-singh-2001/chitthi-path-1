package com.chitthi.usage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Every metric name the Grafana dashboard (infra/grafana/dashboards/chitthi.json)
 * reads comes from here - this proves each one is actually created with the
 * tags the dashboard's queries group by.
 */
class UsageRecorderTest {

    private final ApiCallRepository repository = mock(ApiCallRepository.class);
    private final UsagePricing pricing = new UsagePricing(
            new BigDecimal("0.50"), new BigDecimal("0.002"), new BigDecimal("0.003"));
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final UsageRecorder recorder = new UsageRecorder(repository, pricing, meterRegistry);

    @Test
    void aSuccessfulCallIsCostedAndSavedAndMeteredOnAllFourSeries() {
        UUID documentId = UUID.randomUUID();

        recorder.record(documentId, "translate", 500, UnitType.CHARACTERS, 120, 200);

        ArgumentCaptor<ApiCall> saved = ArgumentCaptor.forClass(ApiCall.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEstCostInr()).isEqualByComparingTo("1.0000");

        assertThat(meterRegistry.get("chitthi.sarvam.calls").tags("endpoint", "translate", "outcome", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("chitthi.sarvam.latency").tags("endpoint", "translate")
                .timer().count()).isEqualTo(1);
        assertThat(meterRegistry.get("chitthi.sarvam.cost.inr").tags("endpoint", "translate")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("chitthi.sarvam.units").tags("unit_type", "CHARACTERS")
                .counter().count()).isEqualTo(500.0);
    }

    @Test
    void aFailedCallRecordsZeroCostAndTheServerErrorOutcome() {
        UUID documentId = UUID.randomUUID();

        recorder.record(documentId, "tts", 100, UnitType.CHARACTERS, 50, 500);

        ArgumentCaptor<ApiCall> saved = ArgumentCaptor.forClass(ApiCall.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEstCostInr()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(meterRegistry.get("chitthi.sarvam.calls").tags("endpoint", "tts", "outcome", "server_error")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void aRateLimitedCallIsTaggedRateLimitedNotClientError() {
        recorder.record(UUID.randomUUID(), "vision-submit", 1, UnitType.PAGES, 10, 429);

        assertThat(meterRegistry.get("chitthi.sarvam.calls").tags("endpoint", "vision-submit", "outcome", "rate_limited")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void aStatusOfZeroIsTaggedIoError() {
        recorder.record(UUID.randomUUID(), "tts", 1, UnitType.CHARACTERS, 10, 0);

        assertThat(meterRegistry.get("chitthi.sarvam.calls").tags("endpoint", "tts", "outcome", "io_error")
                .counter().count()).isEqualTo(1.0);
    }
}
