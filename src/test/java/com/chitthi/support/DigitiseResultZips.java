package com.chitthi.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds synthetic Digitise result ZIPs for {@code DigitiseResultParserTest}
 * and the OCR pipeline integration test, so both exercise the same shape of
 * fixture the real API is expected to return.
 */
public final class DigitiseResultZips {

    private DigitiseResultZips() {
    }

    /** {@code metadata/page_NNN.json} per page, each {@code {"<fieldName>": "<text>"}}, 1-based numbering. */
    public static byte[] perPageJson(int pageCount, IntFunction<String> textForPage, String fieldName) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 1; i <= pageCount; i++) {
            String json = "{\"%s\": %s}".formatted(fieldName, quote(textForPage.apply(i)));
            entries.put("metadata/page_%03d.json".formatted(i), json.getBytes(StandardCharsets.UTF_8));
        }
        entries.put("manifest.json", "{\"page_count\": %d}".formatted(pageCount).getBytes(StandardCharsets.UTF_8));
        return zipOf(entries);
    }

    public static byte[] perPageJson(int pageCount, IntFunction<String> textForPage) {
        return perPageJson(pageCount, textForPage, "markdown");
    }

    /** Same as {@link #perPageJson(int, IntFunction, String)} but 0-based entry numbering ({@code page_000.json}). */
    public static byte[] perPageJsonZeroBased(int pageCount, IntFunction<String> textForPage, String fieldName) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < pageCount; i++) {
            String json = "{\"%s\": %s}".formatted(fieldName, quote(textForPage.apply(i + 1)));
            entries.put("metadata/page_%03d.json".formatted(i), json.getBytes(StandardCharsets.UTF_8));
        }
        return zipOf(entries);
    }

    /** {@code {"result": {"<fieldName>": "<text>"}}} - a plausible response envelope one level deeper. */
    public static byte[] perPageJsonNested(int pageCount, IntFunction<String> textForPage, String fieldName) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 1; i <= pageCount; i++) {
            String json = "{\"result\": {\"%s\": %s}}".formatted(fieldName, quote(textForPage.apply(i)));
            entries.put("metadata/page_%03d.json".formatted(i), json.getBytes(StandardCharsets.UTF_8));
        }
        return zipOf(entries);
    }

    public static byte[] perPageMarkdownFiles(int pageCount, IntFunction<String> textForPage) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 1; i <= pageCount; i++) {
            entries.put("pages/page_%03d.md".formatted(i), textForPage.apply(i).getBytes(StandardCharsets.UTF_8));
        }
        return zipOf(entries);
    }

    public static byte[] singleDelimitedDocument(int pageCount, IntFunction<String> textForPage, String delimiter) {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= pageCount; i++) {
            if (i > 1) {
                content.append(delimiter);
            }
            content.append(textForPage.apply(i));
        }
        return zipOf(Map.of("output.md", content.toString().getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] zipOf(Map<String, byte[]> entries) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new AssertionError("Failed to build test ZIP", e);
        }
        return buffer.toByteArray();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
