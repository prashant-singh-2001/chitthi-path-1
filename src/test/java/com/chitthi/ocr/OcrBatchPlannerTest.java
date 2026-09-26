package com.chitthi.ocr;

import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.service.OcrBatchPlanner;
import com.chitthi.sarvam.SarvamProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OcrBatchPlannerTest {

    // chunk size 10, matching sarvam.pipeline.ocr-max-pages-per-chunk in application.yml
    private final SarvamProperties sarvamProperties = new SarvamProperties(
            "https://api.sarvam.ai", "test-key",
            new SarvamProperties.Http(java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(60)),
            new SarvamProperties.RateLimits(10, 60, 60),
            new SarvamProperties.Pipeline(10, 2000, 2500, 5),
            new SarvamProperties.Translate("sarvam-translate:v1"),
            new SarvamProperties.Tts("bulbul:v3", "shubh", 22050));

    private final OcrProperties ocrProperties = new OcrProperties(
            new OcrProperties.Worker(true, 2, 4),
            new OcrProperties.Poller(true, 20),
            new OcrProperties.Poll(Duration.ofSeconds(5), 1.5, Duration.ofSeconds(60), 0.2, 30),
            new OcrProperties.Dispatch(Duration.ofSeconds(60), 3),
            33_554_432L,
            new OcrProperties.Result(33_554_432L, List.of("markdown")));

    private final OcrBatchPlanner planner = new OcrBatchPlanner(sarvamProperties, ocrProperties);

    @Test
    void twelvePages_splitsIntoTenAndTwo() {
        List<OcrBatch> batches = planner.plan(UUID.randomUUID(), 12);

        assertThat(batches).extracting(OcrBatch::getPageRange).containsExactly("1-10", "11-12");
    }

    @Test
    void tenPages_isOneBatch() {
        List<OcrBatch> batches = planner.plan(UUID.randomUUID(), 10);

        assertThat(batches).extracting(OcrBatch::getPageRange).containsExactly("1-10");
    }

    @Test
    void onePage_isOneBatch() {
        List<OcrBatch> batches = planner.plan(UUID.randomUUID(), 1);

        assertThat(batches).extracting(OcrBatch::getPageRange).containsExactly("1-1");
    }

    @Test
    void elevenPages_splitsIntoTenAndOne() {
        List<OcrBatch> batches = planner.plan(UUID.randomUUID(), 11);

        assertThat(batches).extracting(OcrBatch::getPageRange).containsExactly("1-10", "11-11");
    }

    @Test
    void thirtyPages_splitsIntoThreeFullBatches() {
        List<OcrBatch> batches = planner.plan(UUID.randomUUID(), 30);

        assertThat(batches).extracting(OcrBatch::getPageRange)
                .containsExactly("1-10", "11-20", "21-30");
    }

    @Test
    void allBatches_shareTheDocumentId() {
        UUID documentId = UUID.randomUUID();

        List<OcrBatch> batches = planner.plan(documentId, 12);

        assertThat(batches).extracting(OcrBatch::getDocumentId).containsOnly(documentId);
    }

    @Test
    void zeroPages_isRejected() {
        assertThatThrownBy(() -> planner.plan(UUID.randomUUID(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createdBatches_getADispatchDeadlineBeforeAnyPublishHappens() {
        List<OcrBatch> batches = planner.plan(UUID.randomUUID(), 1);

        // Nothing has been submitted or even published yet, but next_poll_at
        // must already be set - it's what lets the poller's future redispatch
        // sweep notice a batch whose after-commit publish never arrived.
        assertThat(batches.get(0).getNextPollAt()).isAfter(java.time.OffsetDateTime.now());
    }
}
