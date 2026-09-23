package com.chitthi.ocr.model;

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
 * One Sarvam Document AI Digitise job, covering up to
 * {@code sarvam.pipeline.ocr-max-pages-per-chunk} pages of one document. See
 * {@link OcrBatchStatus} for why the status vocabulary is deliberately small.
 */
@Entity
@Table(name = "ocr_batch")
public class OcrBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "page_range", nullable = false)
    private String pageRange;

    @Column(name = "sarvam_job_id")
    private String sarvamJobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OcrBatchStatus status = OcrBatchStatus.PENDING;

    @Column(name = "poll_count", nullable = false)
    private int pollCount = 0;

    @Column(name = "next_poll_at")
    private OffsetDateTime nextPollAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected OcrBatch() {
    }

    public OcrBatch(UUID documentId, String pageRange) {
        this.documentId = documentId;
        this.pageRange = pageRange;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getPageRange() {
        return pageRange;
    }

    public String getSarvamJobId() {
        return sarvamJobId;
    }

    public void setSarvamJobId(String sarvamJobId) {
        this.sarvamJobId = sarvamJobId;
    }

    public OcrBatchStatus getStatus() {
        return status;
    }

    public void setStatus(OcrBatchStatus status) {
        this.status = status;
    }

    public int getPollCount() {
        return pollCount;
    }

    public void setPollCount(int pollCount) {
        this.pollCount = pollCount;
    }

    public OffsetDateTime getNextPollAt() {
        return nextPollAt;
    }

    public void setNextPollAt(OffsetDateTime nextPollAt) {
        this.nextPollAt = nextPollAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
