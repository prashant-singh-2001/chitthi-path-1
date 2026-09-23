package com.chitthi.document;

import com.chitthi.document.service.TextHasher;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;

class TextHasherTest {

    @Test
    void sha256Hex_producesSixtyFourHexCharacters() {
        String hash = TextHasher.sha256Hex("नमस्ते");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    void sha256Hex_isStableForTheSameInput() {
        assertThat(TextHasher.sha256Hex("hello world"))
                .isEqualTo(TextHasher.sha256Hex("hello world"));
    }

    @Test
    void sha256Hex_treatsComposedAndDecomposedFormsAsEqual() {
        String composed = "café"; // é as a single code point
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD); // e + combining acute

        assertThat(TextHasher.sha256Hex(composed)).isEqualTo(TextHasher.sha256Hex(decomposed));
    }

    @Test
    void sha256Hex_ignoresLeadingAndTrailingWhitespace() {
        assertThat(TextHasher.sha256Hex("  hello  ")).isEqualTo(TextHasher.sha256Hex("hello"));
    }

    @Test
    void sha256Hex_differentTextProducesDifferentHash() {
        assertThat(TextHasher.sha256Hex("hello")).isNotEqualTo(TextHasher.sha256Hex("world"));
    }
}
