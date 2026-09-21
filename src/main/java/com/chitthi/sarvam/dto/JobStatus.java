package com.chitthi.sarvam.dto;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum JobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
    REJECTED;

    @JsonCreator
    public static JobStatus fromValue(String value) {
        return JobStatus.valueOf(value.toUpperCase());
    }
}
