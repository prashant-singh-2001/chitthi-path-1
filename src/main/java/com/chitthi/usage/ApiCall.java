package com.chitthi.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One paid (or attempted) Sarvam call, per FR10. Written once per real HTTP
 * call by {@link UsageMeter} - a {@code stage_task} or TTS-cache hit never
 * reaches this table, since it never calls Sarvam at all.
 */
@Entity
@Table(name = "api_call")
public class ApiCall {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(nullable = false)
    private String endpoint;

    @Column(nullable = false)
    private int units;

    @Column(name = "unit_type", nullable = false)
    private String unitType;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(name = "est_cost_inr", nullable = false)
    private BigDecimal estCostInr;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ApiCall() {
    }

    public ApiCall(UUID documentId, String endpoint, int units, String unitType, int latencyMs, int httpStatus,
                   BigDecimal estCostInr) {
        this.documentId = documentId;
        this.endpoint = endpoint;
        this.units = units;
        this.unitType = unitType;
        this.latencyMs = latencyMs;
        this.httpStatus = httpStatus;
        this.estCostInr = estCostInr;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public int getUnits() {
        return units;
    }

    public String getUnitType() {
        return unitType;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public BigDecimal getEstCostInr() {
        return estCostInr;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
