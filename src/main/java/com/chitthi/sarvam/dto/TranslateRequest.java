package com.chitthi.sarvam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TranslateRequest(
        String input,
        @JsonProperty("source_language_code") String sourceLanguageCode,
        @JsonProperty("target_language_code") String targetLanguageCode,
        String model) {
}
