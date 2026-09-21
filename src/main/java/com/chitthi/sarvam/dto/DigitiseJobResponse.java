package com.chitthi.sarvam.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DigitiseJobResponse(@JsonProperty("job_id") String jobId) {
}
