package com.chitthi.ocr.model;

/**
 * Deliberately only three "in flight" values, chosen to fit V1's existing
 * partial index {@code idx_ocr_batch_next_poll ... WHERE status IN
 * ('PENDING','RUNNING')} rather than adding a new index for a richer
 * vocabulary. PENDING is a row created but not yet submitted to Sarvam;
 * RUNNING covers both a submitted-but-not-yet-polled batch and Sarvam's own
 * {@code pending}/{@code running} job states once polling starts. Terminal
 * states mirror {@link com.chitthi.sarvam.dto.JobStatus}, collapsing
 * REJECTED into FAILED since nothing downstream treats them differently.
 */
public enum OcrBatchStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED
}
