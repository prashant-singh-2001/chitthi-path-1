package com.chitthi.sarvam.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TtsResponse(
        @JsonProperty("request_id") String requestId,
        List<String> audios) {
}
