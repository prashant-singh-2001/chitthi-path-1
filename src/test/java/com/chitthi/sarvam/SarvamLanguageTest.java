package com.chitthi.sarvam;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SarvamLanguageTest {

    @Test
    void normalize_acceptsABareIsoCode() {
        assertThat(SarvamLanguage.normalize("hi")).isEqualTo("hi-IN");
    }

    @Test
    void normalize_isCaseInsensitiveAndAcceptsAnAlreadySuffixedCode() {
        assertThat(SarvamLanguage.normalize("HI-in")).isEqualTo("hi-IN");
    }

    @Test
    void normalize_rejectsAnUnknownCode() {
        assertThatThrownBy(() -> SarvamLanguage.normalize("xx"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalize_rejectsABlankCode() {
        assertThatThrownBy(() -> SarvamLanguage.normalize(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isKnown_isTrueForATranslateSupportedCodeAndFalseOtherwise() {
        assertThat(SarvamLanguage.isKnown("hi")).isTrue();
        assertThat(SarvamLanguage.isKnown("zz")).isFalse();
    }

    @Test
    void supportsTts_isTrueOnlyForBulbulsElevenLanguages() {
        assertThat(SarvamLanguage.supportsTts("hi")).isTrue();
        assertThat(SarvamLanguage.supportsTts("hi-IN")).isTrue();
        // "as" (Assamese) is Translate-supported but not one of Bulbul's 11.
        assertThat(SarvamLanguage.supportsTts("as")).isFalse();
    }

    @Test
    void isEnglish_matchesTheEnglishCodeRegardlessOfSuffix() {
        assertThat(SarvamLanguage.isEnglish("en")).isTrue();
        assertThat(SarvamLanguage.isEnglish("en-IN")).isTrue();
        assertThat(SarvamLanguage.isEnglish("hi")).isFalse();
    }
}
