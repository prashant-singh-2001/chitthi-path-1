package com.chitthi.document.web;

/**
 * {@code skipped} counts FAILED pages whose OCR never produced text at all -
 * see {@link com.chitthi.document.service.DocumentRetryService}'s note on
 * why those need a batch-level retry this endpoint doesn't yet do.
 */
public record RetryResponse(int requeued, int skipped) {
}
