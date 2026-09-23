package com.chitthi.ocr.result;

/**
 * One page's text as recovered from a Digitise result ZIP, still indexed by
 * its <b>chunk-relative</b> position (matching the {@code page_NNN} naming
 * used both when submitting and in the result). Absolute-page mapping is the
 * caller's job, via {@link com.chitthi.ocr.model.PageRange#absolutePageNo}.
 */
public record ParsedPage(int chunkRelativeIndex, String text) {
}
