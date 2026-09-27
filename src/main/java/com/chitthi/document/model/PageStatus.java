package com.chitthi.document.model;

/**
 * Mirrors the page state machine in the requirements doc:
 * PENDING -> OCR_DONE -> TRANSLATED -> AUDIO_DONE -> INDEXED, with FAILED
 * reachable from any pre-INDEXED state and INDEXED looping back to
 * OCR_DONE on a user edit.
 *
 * <p>FR15: a page whose OCR text would push its owner over the daily word
 * budget stops at {@code CAPPED} instead of {@code OCR_DONE} - it never
 * enqueues translation, exactly like a page that never recovered any text
 * stops at {@code FAILED} instead. {@link com.chitthi.document.service.DocumentRetryService}
 * re-queues a {@code CAPPED} page once the owner's budget frees up, the same
 * way it re-queues {@code FAILED} pages today.
 */
public enum PageStatus {
    PENDING,
    OCR_DONE,
    TRANSLATED,
    AUDIO_DONE,
    INDEXED,
    FAILED,
    CAPPED
}
