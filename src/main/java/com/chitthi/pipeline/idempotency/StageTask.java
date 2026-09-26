package com.chitthi.pipeline.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One unit of paid Sarvam work: one translate or TTS chunk call, guarded by
 * {@code idempotency_key}'s unique constraint so two workers - or one worker
 * retrying a redelivered message - can never both make the call it names.
 * See {@link StageTaskService} for how a row's lifecycle is driven.
 */
@Entity
@Table(name = "stage_task")
public class StageTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "page_id", nullable = false)
    private UUID pageId;

    @Column(nullable = false)
    private String stage;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private int attempts = 1;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StageTaskStatus status = StageTaskStatus.RUNNING;

    @Column(columnDefinition = "text")
    private String result;

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected StageTask() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getPageId() {
        return pageId;
    }

    public String getStage() {
        return stage;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public StageTaskStatus getStatus() {
        return status;
    }

    public String getResult() {
        return result;
    }

    public OffsetDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
