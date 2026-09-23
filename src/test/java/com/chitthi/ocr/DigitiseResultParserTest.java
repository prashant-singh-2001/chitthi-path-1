package com.chitthi.ocr;

import com.chitthi.ocr.model.PageRange;
import com.chitthi.ocr.result.DigitiseResultParser;
import com.chitthi.ocr.result.DigitiseResultException;
import com.chitthi.ocr.result.ParsedPage;
import com.chitthi.ocr.result.PageTextExtractor;
import com.chitthi.support.DigitiseResultZips;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DigitiseResultParserTest {

    private final OcrProperties ocrProperties = new OcrProperties(
            new OcrProperties.Worker(true, 2, 4),
            new OcrProperties.Poller(true, Duration.ofSeconds(1), 20),
            new OcrProperties.Poll(Duration.ofSeconds(5), 1.5, Duration.ofSeconds(60), 0.2, 30),
            new OcrProperties.Dispatch(Duration.ofSeconds(60), 3),
            33_554_432L,
            new OcrProperties.Result(33_554_432L, List.of("markdown", "text", "content")));

    private final DigitiseResultParser parser = new DigitiseResultParser(
            new PageTextExtractor(ocrProperties), ocrProperties, new ObjectMapper());

    @Test
    void parsesPerPageJson_usingAConfiguredFieldName() {
        byte[] zip = DigitiseResultZips.perPageJson(2, i -> "page " + i + " text", "markdown");

        List<ParsedPage> pages = parser.parse(zip, PageRange.parse("1-2"));

        assertThat(pages).extracting(ParsedPage::chunkRelativeIndex, ParsedPage::text)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, "page 1 text"),
                        org.assertj.core.groups.Tuple.tuple(2, "page 2 text"));
    }

    @Test
    void parsesPerPageJson_withTheFieldNestedUnderResult() {
        byte[] zip = DigitiseResultZips.perPageJsonNested(2, i -> "nested text " + i, "text");

        List<ParsedPage> pages = parser.parse(zip, PageRange.parse("1-2"));

        assertThat(pages).hasSize(2);
        assertThat(pages).extracting(ParsedPage::text).contains("nested text 1", "nested text 2");
    }

    @Test
    void unrecognizedFieldName_fallsBackToTheLongestStringField() {
        // "body" is not in the configured text-fields list at all.
        byte[] zip = DigitiseResultZips.perPageJson(1, i -> "the real page text, quite long", "body");

        List<ParsedPage> pages = parser.parse(zip, PageRange.parse("1-1"));

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).text()).isEqualTo("the real page text, quite long");
    }

    @Test
    void zeroBasedPageNumbering_stillMapsToA1BasedChunkRelativeIndex() {
        byte[] zip = DigitiseResultZips.perPageJsonZeroBased(3, i -> "zero-based page " + i, "markdown");

        List<ParsedPage> pages = parser.parse(zip, PageRange.parse("1-3"));

        assertThat(pages).extracting(ParsedPage::chunkRelativeIndex).containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void partiallyCompletedResult_onlyReturnsThePagesPresent() {
        // Only page 1's metadata exists - simulating Sarvam's partially_completed.
        byte[] zip = DigitiseResultZips.zipOf(java.util.Map.of(
                "metadata/page_001.json", "{\"markdown\": \"page one\"}".getBytes()));

        List<ParsedPage> pages = parser.parse(zip, PageRange.parse("1-2"));

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0).chunkRelativeIndex()).isEqualTo(1);
    }

    @Test
    void fallsBackToPerPageMarkdownFiles_whenNoPerPageJsonExists() {
        byte[] zip = DigitiseResultZips.perPageMarkdownFiles(2, i -> "md file text " + i);

        List<ParsedPage> pages = parser.parse(zip, PageRange.parse("1-2"));

        assertThat(pages).hasSize(2);
        assertThat(pages).extracting(ParsedPage::text).contains("md file text 1", "md file text 2");
    }

    @Test
    void fallsBackToSplittingTheMainDocument_whenTheDelimiterCountMatches() {
        byte[] zip = DigitiseResultZips.singleDelimitedDocument(3, i -> "main doc page " + i, "\n---\n");

        List<ParsedPage> pages = parser.parse(zip, PageRange.parse("1-3"));

        assertThat(pages).extracting(ParsedPage::text)
                .containsExactly("main doc page 1", "main doc page 2", "main doc page 3");
    }

    @Test
    void refusesToSplitTheMainDocument_whenTheDelimiterCountDoesNotMatchTheExpectedPageCount() {
        // Only 2 delimited parts in a ZIP claimed to cover 3 pages: a wrong
        // guess here would silently misfile every page after the first.
        byte[] zip = DigitiseResultZips.singleDelimitedDocument(2, i -> "part " + i, "\n---\n");

        List<ParsedPage> pages = parser.parse(zip, PageRange.parse("1-3"));

        assertThat(pages).isEmpty();
    }

    @Test
    void absoluteMapping_isCorrectForTheSecondChunkNotJustTheFirst() {
        byte[] zip = DigitiseResultZips.perPageJson(2, i -> "second chunk page " + i, "markdown");

        List<ParsedPage> pages = parser.parse(zip, PageRange.parse("11-12"));
        PageRange range = PageRange.parse("11-12");

        assertThat(pages).hasSize(2);
        int page1Absolute = range.absolutePageNo(pages.stream()
                .filter(p -> p.text().endsWith("1")).findFirst().orElseThrow().chunkRelativeIndex());
        int page2Absolute = range.absolutePageNo(pages.stream()
                .filter(p -> p.text().endsWith("2")).findFirst().orElseThrow().chunkRelativeIndex());
        assertThat(page1Absolute).isEqualTo(11);
        assertThat(page2Absolute).isEqualTo(12);
    }

    @Test
    void rejectsAZipWithTooManyEntries() {
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 1001; i++) {
            entries.put("metadata/page_%04d.json".formatted(i), "{}".getBytes());
        }
        byte[] zip = DigitiseResultZips.zipOf(entries);

        assertThatThrownBy(() -> parser.parse(zip, PageRange.parse("1-1")))
                .isInstanceOf(DigitiseResultException.class);
    }
}
