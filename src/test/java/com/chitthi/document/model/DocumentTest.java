package com.chitthi.document.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTest {

    @Test
    void reachingATerminalStatusStampsCompletedAt() {
        Document document = new Document("owner", "title", "hi", null, null);

        document.setStatus(DocumentStatus.COMPLETE);

        assertThat(document.getCompletedAt()).isNotNull();
    }

    @Test
    void goingBackToProcessingClearsCompletedAt() {
        Document document = new Document("owner", "title", "hi", null, null);
        document.setStatus(DocumentStatus.COMPLETE);

        document.setStatus(DocumentStatus.PROCESSING);

        assertThat(document.getCompletedAt()).isNull();
    }

    @Test
    void pendingHasNoCompletedAt() {
        Document document = new Document("owner", "title", "hi", null, null);

        assertThat(document.getCompletedAt()).isNull();
    }
}
