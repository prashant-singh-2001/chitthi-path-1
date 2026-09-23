package com.chitthi.ocr.message;

import java.util.UUID;

/**
 * A pointer, not a payload. The OCR worker re-reads the {@code ocr_batch} row
 * before doing anything, so a stale or duplicated message can never cause a
 * second paid Sarvam call - documentId and language ride along only so the
 * worker can log and submit without an extra query.
 *
 * <p>Image keys are deliberately absent: they can change (FR12 pre-processing
 * will rewrite them), and the database is the only trustworthy source at
 * consume time.
 */
public record OcrBatchMessage(UUID batchId, UUID documentId, String language, String pageRange) {
}
