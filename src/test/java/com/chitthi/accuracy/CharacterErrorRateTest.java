package com.chitthi.accuracy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Plain objects, no Mockito or Docker - runs on any machine. */
class CharacterErrorRateTest {

    @Test
    void identicalTextScoresZero() {
        assertThat(CharacterErrorRate.cer("नमस्ते दुनिया", "नमस्ते दुनिया")).isZero();
        assertThat(CharacterErrorRate.wer("hello world", "hello world")).isZero();
    }

    @Test
    void oneSubstitutionInTenCharactersIsTenPercent() {
        assertThat(CharacterErrorRate.cer("abcdefghij", "abcdefghiX")).isCloseTo(0.1, within(1e-9));
    }

    @Test
    void insertionsAndDeletionsEachCountOnce() {
        assertThat(CharacterErrorRate.cer("abcd", "abcde")).isCloseTo(0.25, within(1e-9));
        assertThat(CharacterErrorRate.cer("abcd", "abd")).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void aMissedDevanagariVowelSignIsOneCodePointError() {
        // "कि" = KA + VOWEL SIGN I; dropping the matra leaves "क".
        assertThat(CharacterErrorRate.characterDistance("कि", "क")).isEqualTo(1);
        assertThat(CharacterErrorRate.cer("कि", "क")).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void canonicallyEquivalentEncodingsAreNotAnError() {
        // U+00E9 against "e" + combining acute.
        assertThat(CharacterErrorRate.cer("café", "café")).isZero();
    }

    @Test
    void whitespaceAndLineWrapsAreIgnored() {
        assertThat(CharacterErrorRate.cer("one two\nthree", "  one   two three ")).isZero();
        assertThat(CharacterErrorRate.wer("one two\nthree", "one two three")).isZero();
    }

    @Test
    void werCountsWholeWords() {
        assertThat(CharacterErrorRate.wer("the cat sat", "the dog sat")).isCloseTo(1.0 / 3, within(1e-9));
    }

    @Test
    void emptyReferenceScoresZeroOnlyAgainstEmptyOutput() {
        assertThat(CharacterErrorRate.cer("", "")).isZero();
        assertThat(CharacterErrorRate.cer("", "x")).isEqualTo(1.0);
        assertThat(CharacterErrorRate.wer("", "x")).isEqualTo(1.0);
    }

    @Test
    void outputMuchLongerThanReferenceCanExceedOne() {
        assertThat(CharacterErrorRate.cer("ab", "abcdef")).isGreaterThan(1.0);
    }
}
