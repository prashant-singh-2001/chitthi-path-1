package com.chitthi.ocr.event;

import java.util.UUID;

/**
 * Raised once per page {@link com.chitthi.ocr.service.OcrResultApplier} marks
 * {@link com.chitthi.document.model.PageStatus#OCR_DONE}, inside the same
 * transaction as that write.
 * {@code com.chitthi.translate.TranslateDispatcher} only acts on this after
 * that transaction commits, so a rolled-back OCR apply never queues
 * translation work for a page whose {@code original_text} was never actually
 * saved.
 */
public record PageOcrCompletedEvent(UUID pageId) {
}
