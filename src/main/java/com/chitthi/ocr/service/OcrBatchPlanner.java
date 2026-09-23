package com.chitthi.ocr.service;

import com.chitthi.ocr.OcrProperties;
import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.model.PageRange;
import com.chitthi.sarvam.SarvamProperties;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Splits a document's pages into the chunks Sarvam Document AI Digitise
 * requires: at most {@code sarvam.pipeline.ocr-max-pages-per-chunk} pages per
 * job. A document's pages are always numbered contiguously from 1 by
 * {@link com.chitthi.document.service.DocumentUploadService}, so chunking by
 * count alone (rather than inspecting individual page numbers) is enough.
 *
 * <p>Each created batch gets {@code nextPollAt = now + chitthi.ocr.dispatch.lease}
 * even though nothing has been submitted yet. That field does double duty: once
 * a job is submitted it becomes the poll schedule, but until then it is what
 * lets {@code OcrStatusPoller}'s redispatch sweep find a batch whose
 * after-commit publish never arrived, or whose worker claimed it and then
 * failed to submit - both leave {@code sarvam_job_id} null with no other
 * signal that anything is wrong.
 */
@Component
public class OcrBatchPlanner {

    private final SarvamProperties sarvamProperties;
    private final OcrProperties ocrProperties;

    public OcrBatchPlanner(SarvamProperties sarvamProperties, OcrProperties ocrProperties) {
        this.sarvamProperties = sarvamProperties;
        this.ocrProperties = ocrProperties;
    }

    public List<OcrBatch> plan(UUID documentId, int totalPages) {
        if (totalPages < 1) {
            throw new IllegalArgumentException("A document must have at least one page to batch");
        }
        int chunkSize = sarvamProperties.pipeline().ocrMaxPagesPerChunk();
        OffsetDateTime dispatchDeadline = OffsetDateTime.now().plus(ocrProperties.dispatch().lease());

        List<OcrBatch> batches = new ArrayList<>();
        for (int start = 1; start <= totalPages; start += chunkSize) {
            int end = Math.min(start + chunkSize - 1, totalPages);
            PageRange range = new PageRange(start, end);
            OcrBatch batch = new OcrBatch(documentId, range.format());
            batch.setNextPollAt(dispatchDeadline);
            batches.add(batch);
        }
        return batches;
    }
}
