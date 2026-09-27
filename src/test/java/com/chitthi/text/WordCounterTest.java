package com.chitthi.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WordCounterTest {

    @Test
    void countsSpaceSeparatedWords() {
        assertThat(WordCounter.count("one two three")).isEqualTo(3);
    }

    @Test
    void collapsesRunsOfWhitespaceAndNewlines() {
        assertThat(WordCounter.count("one\n\ntwo   three\tfour")).isEqualTo(4);
    }

    @Test
    void ignoresLeadingAndTrailingWhitespace() {
        assertThat(WordCounter.count("  one two  ")).isEqualTo(2);
    }

    @Test
    void countsDevanagariText() {
        assertThat(WordCounter.count("यह एक पत्र है")).isEqualTo(4);
    }

    @Test
    void blankTextIsZeroWords() {
        assertThat(WordCounter.count("")).isZero();
        assertThat(WordCounter.count("   ")).isZero();
        assertThat(WordCounter.count(null)).isZero();
    }

    @Test
    void singleWordIsOne() {
        assertThat(WordCounter.count("hello")).isEqualTo(1);
    }
}
