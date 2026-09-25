package com.chitthi.document.model;

public enum DocumentStatus {
    PENDING,
    PROCESSING,
    PARTIAL,
    COMPLETE;

    /** No further page transition will ever change a document past this status. */
    public boolean isTerminal() {
        return this == PARTIAL || this == COMPLETE;
    }
}
