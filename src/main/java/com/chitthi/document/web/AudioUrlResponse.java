package com.chitthi.document.web;

import java.time.OffsetDateTime;

/**
 * {@code fallback} is true when {@code orig} was requested but no
 * original-language track exists (Bulbul doesn't support the document's
 * source language) and the English track was returned instead - the UI's cue
 * to show its Bulbul-coverage notice rather than presenting English as if it
 * were the original.
 */
public record AudioUrlResponse(String url, OffsetDateTime expiresAt, boolean fallback) {
}
