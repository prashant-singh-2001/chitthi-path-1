package com.chitthi.sarvam.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TranslateResponse(@JsonProperty("translated_text") String translatedText) {
}
