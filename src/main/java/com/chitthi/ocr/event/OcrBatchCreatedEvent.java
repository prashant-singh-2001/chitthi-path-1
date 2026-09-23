package com.chitthi.ocr.event;

import java.util.UUID;

/**
 * Raised once per {@code ocr_batch} row inside the same transaction that
 * creates it. {@link com.chitthi.ocr.service.OcrDispatcher} only acts on this
 * after that transaction commits, so a rolled-back upload never queues OCR
 * work for pages that were never actually saved.
 */
public record OcrBatchCreatedEvent(UUID batchId, UUID documentId, String language, String pageRange) {
}
