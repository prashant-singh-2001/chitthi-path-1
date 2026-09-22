package com.chitthi.document.model;

/**
 * Mirrors the page state machine in the requirements doc:
 * PENDING -> OCR_DONE -> TRANSLATED -> AUDIO_DONE -> INDEXED, with FAILED
 * reachable from any pre-INDEXED state and INDEXED looping back to
 * OCR_DONE on a user edit.
 */
public enum PageStatus {
    PENDING,
    OCR_DONE,
    TRANSLATED,
    AUDIO_DONE,
    INDEXED,
    FAILED
}
