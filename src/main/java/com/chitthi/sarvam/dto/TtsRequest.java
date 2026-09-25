package com.chitthi.sarvam.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TtsRequest(
        String text,
        @JsonProperty("language_code") String languageCode,
        String speaker,
        String model,
        @JsonProperty("speech_sample_rate") int speechSampleRate,
        @JsonProperty("output_audio_codec") String outputAudioCodec) {
}
