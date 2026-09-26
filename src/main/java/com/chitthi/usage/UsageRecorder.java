package com.chitthi.usage;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The actual write: one {@code api_call} row plus the Micrometer series the
 * Grafana dashboard reads. {@code REQUIRES_NEW} because every caller
 * ({@link com.chitthi.ocr.service.OcrWorker}, {@link com.chitthi.translate.TranslateWorker},
 * {@link com.chitthi.tts.TtsWorker}) runs the Sarvam call itself with no
 * transaction open - the same reasoning those workers already follow for
 * every other database write around a slow external call.
 */
@Service
public class UsageRecorder {

    private final ApiCallRepository repository;
    private final UsagePricing pricing;
    private final MeterRegistry meterRegistry;

    public UsageRecorder(ApiCallRepository repository, UsagePricing pricing, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.pricing = pricing;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID documentId, String endpoint, int units, UnitType unitType, int latencyMs, int httpStatus) {
        boolean success = httpStatus >= 200 && httpStatus < 300;
        BigDecimal cost = success ? pricing.costFor(endpoint, units) : BigDecimal.ZERO;

        repository.save(new ApiCall(documentId, endpoint, units, unitType.name(), latencyMs, httpStatus, cost));

        meterRegistry.counter("chitthi.sarvam.calls", "endpoint", endpoint, "outcome", outcomeOf(httpStatus)).increment();
        meterRegistry.timer("chitthi.sarvam.latency", "endpoint", endpoint)
                .record(latencyMs, TimeUnit.MILLISECONDS);
        meterRegistry.counter("chitthi.sarvam.cost.inr", "endpoint", endpoint).increment(cost.doubleValue());
        meterRegistry.counter("chitthi.sarvam.units", "unit_type", unitType.name()).increment(units);
    }

    /**
     * {@code 0} means the call never got an HTTP response at all (a timeout,
     * a connection failure) - not a Sarvam error code, so it's kept distinct
     * from every real 4xx/5xx.
     */
    private String outcomeOf(int httpStatus) {
        if (httpStatus == 0) {
            return "io_error";
        }
        if (httpStatus == 429) {
            return "rate_limited";
        }
        if (httpStatus >= 200 && httpStatus < 300) {
            return "success";
        }
        if (httpStatus >= 400 && httpStatus < 500) {
            return "client_error";
        }
        return "server_error";
    }
}
