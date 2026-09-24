package com.chitthi.sarvam;

import java.util.Locale;
import java.util.Set;

/**
 * Normalizes a document's source language to the {@code xx-IN} form Sarvam's
 * Translate and Text-to-Speech APIs require, and reports which of Sarvam's
 * language sets a code belongs to.
 *
 * <p>Digitise (OCR) is unaffected by normalization - it already accepts the
 * bare code documents are uploaded with (see {@link SarvamClient#submitDigitiseJob}),
 * so this class is only consulted at upload validation (to reject an unknown
 * code early) and by the translate/TTS stages (to build the suffixed code
 * those APIs actually require).
 */
public final class SarvamLanguage {

    // Sarvam Translate's 22 supported languages (docs.sarvam.ai, 2026-09-24).
    private static final Set<String> TRANSLATE_SUPPORTED = Set.of(
            "as", "bn", "brx", "doi", "en", "gu", "hi", "kn", "kok", "ks",
            "mai", "ml", "mni", "mr", "ne", "od", "pa", "sa", "sd", "ta",
            "te", "ur");

    // Bulbul TTS's 11 supported languages - a strict subset of Translate's.
    private static final Set<String> TTS_SUPPORTED = Set.of(
            "bn", "en", "gu", "hi", "kn", "ml", "mr", "od", "pa", "ta", "te");

    private static final String ENGLISH = "en";

    private SarvamLanguage() {
    }

    /**
     * Normalizes a bare ISO code ({@code "hi"}) or an already-suffixed one
     * ({@code "hi-IN"}, any case) to Sarvam's canonical {@code "hi-IN"} form.
     *
     * @throws IllegalArgumentException if the code isn't one Translate supports
     */
    public static String normalize(String code) {
        String base = baseCode(code);
        if (!TRANSLATE_SUPPORTED.contains(base)) {
            throw new IllegalArgumentException("Unsupported language code: " + code);
        }
        return base + "-IN";
    }

    public static boolean isKnown(String code) {
        try {
            normalize(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean supportsTts(String code) {
        return TTS_SUPPORTED.contains(baseCode(code));
    }

    public static boolean isEnglish(String code) {
        return ENGLISH.equals(baseCode(code));
    }

    private static String baseCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Language code must not be blank");
        }
        String lower = code.trim().toLowerCase(Locale.ROOT);
        int dash = lower.indexOf('-');
        return dash >= 0 ? lower.substring(0, dash) : lower;
    }
}
