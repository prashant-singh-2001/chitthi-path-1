package com.chitthi.ocr;

import com.chitthi.ocr.model.PageRange;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageRangeTest {

    @Test
    void format_roundTripsThroughParse() {
        PageRange range = new PageRange(11, 12);

        assertThat(range.format()).isEqualTo("11-12");
        assertThat(PageRange.parse("11-12")).isEqualTo(range);
    }

    @Test
    void singlePageRange_formatsWithEqualEndpoints() {
        PageRange range = new PageRange(5, 5);

        assertThat(range.format()).isEqualTo("5-5");
        assertThat(range.size()).isEqualTo(1);
    }

    @Test
    void parse_acceptsCommaSeparatedPages() {
        PageRange range = PageRange.parse("3,7,9");

        assertThat(range.firstPage()).isEqualTo(3);
        assertThat(range.lastPage()).isEqualTo(9);
    }

    @Test
    void absolutePageNo_mapsChunkRelativeIndexAtBothEnds() {
        PageRange firstChunk = PageRange.parse("1-10");
        assertThat(firstChunk.absolutePageNo(1)).isEqualTo(1);
        assertThat(firstChunk.absolutePageNo(10)).isEqualTo(10);

        // The off-by-one that matters: mapping within the *second* chunk.
        PageRange secondChunk = PageRange.parse("11-12");
        assertThat(secondChunk.absolutePageNo(1)).isEqualTo(11);
        assertThat(secondChunk.absolutePageNo(2)).isEqualTo(12);
    }

    @Test
    void contains_checksInclusiveBounds() {
        PageRange range = PageRange.parse("11-12");

        assertThat(range.contains(11)).isTrue();
        assertThat(range.contains(12)).isTrue();
        assertThat(range.contains(10)).isFalse();
        assertThat(range.contains(13)).isFalse();
    }

    @Test
    void parse_rejectsMalformedInput() {
        assertThatThrownBy(() -> PageRange.parse("not-a-range"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsInvertedRange() {
        assertThatThrownBy(() -> new PageRange(10, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
