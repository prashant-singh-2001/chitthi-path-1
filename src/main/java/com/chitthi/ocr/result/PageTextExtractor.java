package com.chitthi.ocr.result;

import com.chitthi.ocr.OcrProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one place the Digitise per-page output's field name is guessed, because
 * the requirements document names the ZIP's structure ({@code
 * metadata/page_NNN.json}, {@code manifest.json}, {@code output_format=md})
 * but not which JSON field inside a page's metadata actually holds its text.
 *
 * <p>The candidate list is {@code chitthi.ocr.result.text-fields}, read in
 * order - <b>configuration, not code</b> - so pinning the real field name
 * (see {@code SarvamDigitiseSmokeTest}) after one real Digitise call is a
 * one-line YAML edit, not a redeploy. If nothing on that list matches, this
 * falls back to the longest string value anywhere in the object and logs
 * every field name and value length it saw at WARN - that log line is the
 * plan for narrowing the guess.
 */
@Component
public class PageTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PageTextExtractor.class);

    /** Sarvam's response envelope, if any, is guessed to be one of these one level down. */
    private static final List<String> NESTING_PATHS = List.of("data", "result", "page");

    private final OcrProperties ocrProperties;

    public PageTextExtractor(OcrProperties ocrProperties) {
        this.ocrProperties = ocrProperties;
    }

    public Optional<String> extractText(JsonNode pageJson) {
        for (String field : ocrProperties.result().textFields()) {
            Optional<String> direct = textAt(pageJson, field);
            if (direct.isPresent()) {
                return direct;
            }
        }
        for (String nestPath : NESTING_PATHS) {
            JsonNode nested = pageJson.get(nestPath);
            if (nested != null && nested.isObject()) {
                for (String field : ocrProperties.result().textFields()) {
                    Optional<String> value = textAt(nested, field);
                    if (value.isPresent()) {
                        return value;
                    }
                }
            }
        }

        Optional<Map.Entry<String, String>> longest = longestStringField(pageJson, "");
        if (longest.isPresent()) {
            log.warn("No configured text field ({}) matched a page's JSON; falling back to the longest string "
                            + "field '{}' ({} chars). Observed fields: {}",
                    ocrProperties.result().textFields(), longest.get().getKey(), longest.get().getValue().length(),
                    fieldSummary(pageJson));
            return Optional.of(longest.get().getValue());
        }

        log.warn("No text field found anywhere in a page's JSON. Observed fields: {}", fieldSummary(pageJson));
        return Optional.empty();
    }

    private Optional<String> textAt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value != null && value.isTextual() && !value.asText().isBlank()) {
            return Optional.of(value.asText());
        }
        return Optional.empty();
    }

    private Optional<Map.Entry<String, String>> longestStringField(JsonNode node, String path) {
        String bestKey = null;
        String bestValue = null;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            JsonNode value = entry.getValue();
            if (value.isTextual() && (bestValue == null || value.asText().length() > bestValue.length())) {
                bestKey = key;
                bestValue = value.asText();
            } else if (value.isObject()) {
                Optional<Map.Entry<String, String>> nested = longestStringField(value, key);
                if (nested.isPresent() && (bestValue == null || nested.get().getValue().length() > bestValue.length())) {
                    bestKey = nested.get().getKey();
                    bestValue = nested.get().getValue();
                }
            }
        }
        return bestValue != null && !bestValue.isBlank() ? Optional.of(Map.entry(bestKey, bestValue)) : Optional.empty();
    }

    private String fieldSummary(JsonNode node) {
        StringBuilder summary = new StringBuilder();
        node.fieldNames().forEachRemaining(name -> {
            if (!summary.isEmpty()) {
                summary.append(", ");
            }
            JsonNode value = node.get(name);
            String description = value.isTextual() ? value.asText().length() + " chars" : value.getNodeType().toString();
            summary.append(name).append('=').append(description);
        });
        return summary.toString();
    }
}
