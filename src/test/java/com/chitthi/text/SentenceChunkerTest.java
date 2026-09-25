package com.chitthi.text;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceChunkerTest {

    @Test
    void splitsOnDandaAndPacksGreedilyUpToTheLimit() {
        String text = "यह पहला वाक्य है। यह दूसरा वाक्य है। यह तीसरा वाक्य है।";

        List<String> chunks = SentenceChunker.chunk(text, 40);

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(40));
        assertThat(String.join(" ", chunks)).isEqualTo(text);
    }

    @Test
    void splitsOnLatinSentenceEnds() {
        String text = "First sentence. Second sentence! Third sentence?";

        List<String> chunks = SentenceChunker.chunk(text, 20);

        assertThat(chunks).containsExactly("First sentence.", "Second sentence!", "Third sentence?");
    }

    @Test
    void wholeTextFitsInOneChunkWhenUnderTheLimit() {
        String text = "One short sentence.";

        List<String> chunks = SentenceChunker.chunk(text, 2000);

        assertThat(chunks).containsExactly("One short sentence.");
    }

    @Test
    void hardSplitsASentenceLongerThanTheLimitAtTheLastWhitespace() {
        String longSentence = "word ".repeat(20).strip() + ".";

        List<String> chunks = SentenceChunker.chunk(longSentence, 30);

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(30));
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk).isNotBlank());
    }

    @Test
    void hardSplitsAtTheLimitWhenThereIsNoWhitespaceToBreakOn() {
        String noWhitespace = "a".repeat(50);

        List<String> chunks = SentenceChunker.chunk(noWhitespace, 20);

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(20));
        assertThat(String.join("", chunks)).isEqualTo(noWhitespace);
    }

    @Test
    void neverReturnsABlankChunk() {
        String text = "Sentence one.\n\n\nSentence two.";

        List<String> chunks = SentenceChunker.chunk(text, 2000);

        assertThat(chunks).noneMatch(String::isBlank);
    }
}
