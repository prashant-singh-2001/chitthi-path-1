package com.chitthi.ocr.result;

import com.chitthi.ocr.OcrProperties;
import com.chitthi.ocr.model.PageRange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parses a Sarvam Document AI Digitise result ZIP into per-page text. Tries
 * strategies in order of how well-documented they are, and stops at the
 * first one that recovers at least one page - each logs which one fired.
 * Never guesses at a page split it cannot verify (see {@link #splitMainDocument}):
 * a silently mis-split document would be worse than a batch that reports no
 * pages recovered.
 *
 * <p>Ordinals in {@code page_NNN} names are normalized against the observed
 * minimum, so both 0-based ({@code page_000}) and 1-based ({@code page_001})
 * numbering map onto {@link ParsedPage#chunkRelativeIndex()} the same way.
 */
@Component
public class DigitiseResultParser {

    private static final Logger log = LoggerFactory.getLogger(DigitiseResultParser.class);

    private static final int MAX_ENTRIES = 1000;
    private static final Pattern PER_PAGE_JSON = Pattern.compile("(?i).*page_(\\d+)\\.json$");
    private static final Pattern PER_PAGE_TEXT = Pattern.compile("(?i).*page_(\\d+)\\.(md|txt)$");
    private static final List<String> MAIN_DOCUMENT_DELIMITERS = List.of("\n---\n", "\f", "<!-- page -->");

    private final PageTextExtractor pageTextExtractor;
    private final OcrProperties ocrProperties;
    private final ObjectMapper objectMapper;

    public DigitiseResultParser(PageTextExtractor pageTextExtractor, OcrProperties ocrProperties,
                                 ObjectMapper objectMapper) {
        this.pageTextExtractor = pageTextExtractor;
        this.ocrProperties = ocrProperties;
        this.objectMapper = objectMapper;
    }

    public List<ParsedPage> parse(byte[] zipBytes, PageRange range) {
        Map<String, byte[]> entries = readEntries(zipBytes);
        List<ParsedPage> pages = parseByStrategy(entries, range);
        crossCheckManifest(entries, pages.size(), range);
        return pages;
    }

    private List<ParsedPage> parseByStrategy(Map<String, byte[]> entries, PageRange range) {
        List<ParsedPage> perPageJson = parsePerPageJson(entries);
        if (!perPageJson.isEmpty()) {
            log.debug("Parsed {} of {} pages from per-page metadata JSON", perPageJson.size(), range.size());
            return perPageJson;
        }

        List<ParsedPage> perPageText = parsePerPageText(entries);
        if (!perPageText.isEmpty()) {
            log.debug("Parsed {} of {} pages from per-page text files", perPageText.size(), range.size());
            return perPageText;
        }

        List<ParsedPage> splitMain = splitMainDocument(entries, range.size());
        if (!splitMain.isEmpty()) {
            log.debug("Parsed {} pages by splitting the main document", splitMain.size());
            return splitMain;
        }

        log.warn("No OCR text could be recovered from the result ZIP for range {}. Entries seen: {}",
                range.format(), entries.keySet());
        return List.of();
    }

    /**
     * manifest.json's own shape isn't documented either, so this only ever
     * logs a mismatch - it is a diagnostic aid, never a reason to fail or
     * alter the parsed result.
     */
    private void crossCheckManifest(Map<String, byte[]> entries, int parsedCount, PageRange range) {
        entries.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().endsWith("manifest.json"))
                .findFirst()
                .ifPresent(manifestEntry -> {
                    try {
                        JsonNode manifest = objectMapper.readTree(manifestEntry.getValue());
                        Integer manifestCount = null;
                        if (manifest.has("page_count") && manifest.get("page_count").isInt()) {
                            manifestCount = manifest.get("page_count").asInt();
                        } else if (manifest.has("pages") && manifest.get("pages").isArray()) {
                            manifestCount = manifest.get("pages").size();
                        }
                        if (manifestCount != null && manifestCount != parsedCount) {
                            log.warn("manifest.json reports {} pages but {} were parsed for range {}",
                                    manifestCount, parsedCount, range.format());
                        }
                    } catch (IOException e) {
                        log.debug("Could not parse manifest.json for a page-count cross-check: {}", e.getMessage());
                    }
                });
    }

    private List<ParsedPage> parsePerPageJson(Map<String, byte[]> entries) {
        TreeMap<Integer, String> byOrdinal = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            Matcher matcher = PER_PAGE_JSON.matcher(entry.getKey());
            if (!matcher.matches()) {
                continue;
            }
            int ordinal = Integer.parseInt(matcher.group(1));
            try {
                JsonNode json = objectMapper.readTree(entry.getValue());
                pageTextExtractor.extractText(json).ifPresent(text -> byOrdinal.put(ordinal, text));
            } catch (IOException e) {
                log.warn("Failed to parse per-page JSON entry '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        return toChunkRelativePages(byOrdinal);
    }

    private List<ParsedPage> parsePerPageText(Map<String, byte[]> entries) {
        TreeMap<Integer, String> byOrdinal = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            Matcher matcher = PER_PAGE_TEXT.matcher(entry.getKey());
            if (!matcher.matches()) {
                continue;
            }
            int ordinal = Integer.parseInt(matcher.group(1));
            String text = new String(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8);
            if (!text.isBlank()) {
                byOrdinal.put(ordinal, text);
            }
        }
        return toChunkRelativePages(byOrdinal);
    }

    /**
     * Only accepted when a delimiter splits the largest top-level {@code .md}
     * file into <b>exactly</b> {@code expectedPageCount} parts - anything else
     * means this guess is wrong for this document, and returning a
     * mis-aligned split would be worse than reporting nothing.
     */
    private List<ParsedPage> splitMainDocument(Map<String, byte[]> entries, int expectedPageCount) {
        return entries.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().endsWith(".md") && !PER_PAGE_TEXT.matcher(e.getKey()).matches())
                .max(Comparator.comparingInt(e -> e.getValue().length))
                .flatMap(mainEntry -> {
                    String content = new String(mainEntry.getValue(), java.nio.charset.StandardCharsets.UTF_8);
                    for (String delimiter : MAIN_DOCUMENT_DELIMITERS) {
                        String[] parts = content.split(Pattern.quote(delimiter));
                        if (parts.length == expectedPageCount) {
                            List<ParsedPage> pages = new ArrayList<>();
                            for (int i = 0; i < parts.length; i++) {
                                pages.add(new ParsedPage(i + 1, parts[i].strip()));
                            }
                            return java.util.Optional.of(pages);
                        }
                    }
                    return java.util.Optional.<List<ParsedPage>>empty();
                })
                .orElse(List.of());
    }

    private List<ParsedPage> toChunkRelativePages(TreeMap<Integer, String> byOrdinal) {
        if (byOrdinal.isEmpty()) {
            return List.of();
        }
        int origin = byOrdinal.firstKey();
        List<ParsedPage> pages = new ArrayList<>();
        byOrdinal.forEach((ordinal, text) -> pages.add(new ParsedPage(ordinal - origin + 1, text)));
        return pages;
    }

    private Map<String, byte[]> readEntries(byte[] zipBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long totalUncompressed = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (++count > MAX_ENTRIES) {
                    throw new DigitiseResultException("Result ZIP has more than " + MAX_ENTRIES + " entries");
                }
                byte[] content = readLimited(zip, ocrProperties.result().maxUnzippedBytes() - totalUncompressed);
                totalUncompressed += content.length;
                if (totalUncompressed > ocrProperties.result().maxUnzippedBytes()) {
                    throw new DigitiseResultException(
                            "Result ZIP exceeds the " + ocrProperties.result().maxUnzippedBytes() + " byte uncompressed limit");
                }
                entries.put(entry.getName(), content);
            }
        } catch (IOException e) {
            throw new DigitiseResultException("Failed to read OCR result ZIP", e);
        }
        return entries;
    }

    private byte[] readLimited(InputStream in, long remainingBudget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long written = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            written += read;
            if (written > remainingBudget) {
                throw new DigitiseResultException(
                        "Result ZIP exceeds the " + ocrProperties.result().maxUnzippedBytes() + " byte uncompressed limit");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
