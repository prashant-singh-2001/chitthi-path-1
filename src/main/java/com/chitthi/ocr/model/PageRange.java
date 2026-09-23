package com.chitthi.ocr.model;

import java.util.regex.Pattern;

/**
 * The contract stored in {@code ocr_batch.page_range}: an inclusive, 1-based
 * range of a document's absolute page numbers, formatted as {@code "1-10"}.
 * {@link #absolutePageNo} is the single place the chunk-relative index of a
 * page inside a submitted Sarvam job maps back to an absolute page number -
 * every OCR result parsed later funnels through this method, so an off-by-one
 * here would misfile every page after the first chunk.
 *
 * <p>{@link #parse} also accepts a comma-separated list of individual pages
 * (e.g. {@code "3,7,9"}), which {@link #format} never produces today. That
 * form exists so a future "retry only the dead-lettered pages of a batch"
 * feature (Day 8-9) can build a sparse batch without a schema change - ten
 * two-digit page numbers still fits the column's {@code VARCHAR(50)}.
 */
public record PageRange(int firstPage, int lastPage) {

    private static final Pattern DASH_FORM = Pattern.compile("(\\d+)-(\\d+)");
    private static final Pattern COMMA_FORM = Pattern.compile("\\d+(,\\d+)*");

    public PageRange {
        if (firstPage < 1 || lastPage < firstPage) {
            throw new IllegalArgumentException(
                    "Invalid page range: firstPage=%d, lastPage=%d".formatted(firstPage, lastPage));
        }
    }

    public static PageRange parse(String value) {
        var dashMatch = DASH_FORM.matcher(value.strip());
        if (dashMatch.matches()) {
            return new PageRange(Integer.parseInt(dashMatch.group(1)), Integer.parseInt(dashMatch.group(2)));
        }
        if (COMMA_FORM.matcher(value.strip()).matches()) {
            int[] pages = java.util.Arrays.stream(value.strip().split(","))
                    .mapToInt(Integer::parseInt)
                    .sorted()
                    .toArray();
            return new PageRange(pages[0], pages[pages.length - 1]);
        }
        throw new IllegalArgumentException("Unrecognized page range: " + value);
    }

    /** {@code "1-10"}. Refuses nothing here - every {@link PageRange} this type can hold is contiguous by construction. */
    public String format() {
        return "%d-%d".formatted(firstPage, lastPage);
    }

    public int size() {
        return lastPage - firstPage + 1;
    }

    public boolean contains(int absolutePageNo) {
        return absolutePageNo >= firstPage && absolutePageNo <= lastPage;
    }

    /**
     * Maps a page's position within this chunk to an absolute document page
     * number. {@code chunkRelativeIndex} is 1-based, matching both the ZIP
     * entry naming ({@code page_001.png}, ...) and Sarvam's own per-page
     * output numbering.
     */
    public int absolutePageNo(int chunkRelativeIndex) {
        return firstPage + chunkRelativeIndex - 1;
    }
}
