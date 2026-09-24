package com.chitthi.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits text into chunks no longer than a caller-given character limit,
 * breaking at sentence boundaries wherever possible so Translate and TTS -
 * both of which cap request size - never see a sentence cut mid-word unless
 * the sentence itself exceeds the limit.
 */
public final class SentenceChunker {

    // Danda (।), double danda (॥), '.', '?' or '!' followed by whitespace or
    // end of string, or a bare newline - covers Devanagari-script text (the
    // requirements' primary target) as well as English/Latin punctuation.
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[।॥.?!])\\s+|\\n+");

    private SentenceChunker() {
    }

    public static List<String> chunk(String text, int maxChars) {
        List<String> sentences = splitIntoSentences(text);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence.length() > maxChars) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.addAll(hardSplit(sentence, maxChars));
                continue;
            }
            int prospectiveLength = current.isEmpty() ? sentence.length() : current.length() + 1 + sentence.length();
            if (prospectiveLength > maxChars) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_BOUNDARY.matcher(text);
        int start = 0;
        while (matcher.find()) {
            addIfNotBlank(sentences, text.substring(start, matcher.end()));
            start = matcher.end();
        }
        addIfNotBlank(sentences, text.substring(start));
        return sentences;
    }

    private static void addIfNotBlank(List<String> sentences, String candidate) {
        String trimmed = candidate.strip();
        if (!trimmed.isEmpty()) {
            sentences.add(trimmed);
        }
    }

    /**
     * Splits a single sentence too long to fit any chunk on its own, at the
     * last whitespace before the limit when one exists, or at the limit
     * itself when the sentence has no whitespace to break on.
     */
    private static List<String> hardSplit(String sentence, int maxChars) {
        List<String> parts = new ArrayList<>();
        String remaining = sentence;
        while (remaining.length() > maxChars) {
            int breakAt = remaining.lastIndexOf(' ', maxChars);
            if (breakAt <= 0) {
                breakAt = maxChars;
            }
            addIfNotBlank(parts, remaining.substring(0, breakAt));
            remaining = remaining.substring(breakAt).stripLeading();
        }
        addIfNotBlank(parts, remaining);
        return parts;
    }
}
