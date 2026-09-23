package com.chitthi.ocr;

import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.service.OcrBatchPlanner;
import com.chitthi.sarvam.SarvamProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OcrBatchPlannerTest {

    // chunk size 10, matching sarvam.pipeline.ocr-max-pages-per-chunk in application.yml
    private final SarvamProperties properties = new SarvamProperties(
            "https://api.sarvam.ai", "test-key",
            new SarvamProperties.RateLimits(10),
            new SarvamProperties.Pipeline(10, 2000, 2500, 5));

    private final OcrBatchPlanner planner = new OcrBatchPlanner(properties);

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
}
