package com.chitthi.accuracy;

import java.text.Normalizer;

/**
 * Character and word error rates for scoring OCR output against a human
 * transcription: edit distance divided by the reference's length.
 *
 * <p>Both strings are NFC-normalised and have whitespace collapsed first, so
 * a different but canonically-equal encoding of the same Devanagari text, or
 * a different line wrap, is not counted as an error. Distance is over Unicode
 * <b>code points</b>, not grapheme clusters: in an Indic script a missed
 * vowel sign (matra) is one error, and a conjunct is several code points.
 * Treat the figure as "code-point error rate", comparable between runs but
 * not with tools that count graphemes.
 */
final class CharacterErrorRate {

    private CharacterErrorRate() {
    }

    /** Edit distance over code points / reference code points. Can exceed 1.0 when the output is much longer than the reference. */
    static double cer(String reference, String hypothesis) {
        int[] ref = normalise(reference).codePoints().toArray();
        int[] hyp = normalise(hypothesis).codePoints().toArray();
        return rate(ref.length, distance(ref, hyp));
    }

    /** Edit distance over whitespace-separated tokens / reference tokens. */
    static double wer(String reference, String hypothesis) {
        String[] ref = tokens(reference);
        String[] hyp = tokens(hypothesis);
        int[] refIds = new int[ref.length];
        int[] hypIds = new int[hyp.length];
        java.util.Map<String, Integer> ids = new java.util.HashMap<>();
        for (int i = 0; i < ref.length; i++) {
            refIds[i] = ids.computeIfAbsent(ref[i], k -> ids.size());
        }
        for (int i = 0; i < hyp.length; i++) {
            hypIds[i] = ids.computeIfAbsent(hyp[i], k -> ids.size());
        }
        return rate(refIds.length, distance(refIds, hypIds));
    }

    /** Raw code-point edit distance after normalisation; the CER numerator, exposed for micro-averaging. */
    static int characterDistance(String reference, String hypothesis) {
        return distance(normalise(reference).codePoints().toArray(), normalise(hypothesis).codePoints().toArray());
    }

    static int referenceLength(String reference) {
        return (int) normalise(reference).codePoints().count();
    }

    static String normalise(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("\\s+", " ").strip();
    }

    private static String[] tokens(String text) {
        String normalised = normalise(text);
        return normalised.isEmpty() ? new String[0] : normalised.split(" ");
    }

    /** An empty reference scores 0 against an empty output and 1 against anything else - there is nothing to divide by. */
    private static double rate(int referenceLength, int distance) {
        if (referenceLength == 0) {
            return distance == 0 ? 0.0 : 1.0;
        }
        return (double) distance / referenceLength;
    }

    private static int distance(int[] a, int[] b) {
        int[] previous = new int[b.length + 1];
        int[] current = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length; i++) {
            current[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int substitution = previous[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length];
    }
}
