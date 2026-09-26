package com.chitthi.document.service;

/**
 * The page hasn't come back from OCR yet (still PENDING), so there's no
 * {@code original_text} to correct. Once OCR either succeeds or gives up
 * (marking the page FAILED), an edit is allowed - a FAILED page with no
 * recovered text is exactly when typing the text in by hand is the recovery
 * path.
 */
public class PageNotReadyException extends RuntimeException {

    public PageNotReadyException(String message) {
        super(message);
    }
}
