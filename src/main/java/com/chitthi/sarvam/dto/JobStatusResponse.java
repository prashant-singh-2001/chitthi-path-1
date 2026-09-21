package com.chitthi.sarvam.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JobStatusResponse(@JsonProperty("job_id") String jobId, JobStatus status) {
}
