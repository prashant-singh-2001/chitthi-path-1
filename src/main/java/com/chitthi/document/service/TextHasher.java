package com.chitthi.document.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/**
 * SHA-256 hex over NFC-normalized, trimmed text - {@code page.text_hash}'s
 * value, and later the key FR13's TTS cache looks text up by. NFC
 * normalization means a whitespace- or composition-only difference (the same
 * character built from different Unicode code points) doesn't change the
 * hash, so it doesn't invalidate a cached translation or audio track that
 * would otherwise regenerate for no real change in meaning.
 */
public final class TextHasher {

    private TextHasher() {
    }

    public static String sha256Hex(String text) {
        if (text == null) {
            return null;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC).strip();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
        }
    }
}
