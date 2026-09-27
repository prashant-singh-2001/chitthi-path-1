package com.chitthi.text;

/**
 * FR15 counts "processed words", which for every script this project
 * supports (Devanagari and the other Indic scripts, as well as English) is
 * whitespace-separated tokens - none of them use scriptio continua.
 */
public final class WordCounter {

    private WordCounter() {
    }

    public static int count(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }
}
