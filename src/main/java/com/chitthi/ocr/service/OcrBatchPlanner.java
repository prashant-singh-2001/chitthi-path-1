package com.chitthi.ocr.service;

import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.model.PageRange;
import com.chitthi.sarvam.SarvamProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Splits a document's pages into the chunks Sarvam Document AI Digitise
 * requires: at most {@code sarvam.pipeline.ocr-max-pages-per-chunk} pages per
 * job. A document's pages are always numbered contiguously from 1 by
 * {@link com.chitthi.document.service.DocumentUploadService}, so chunking by
 * count alone (rather than inspecting individual page numbers) is enough.
 */
@Component
public class OcrBatchPlanner {

    private final SarvamProperties sarvamProperties;

    public OcrBatchPlanner(SarvamProperties sarvamProperties) {
        this.sarvamProperties = sarvamProperties;
    }

    public List<OcrBatch> plan(UUID documentId, int totalPages) {
        if (totalPages < 1) {
            throw new IllegalArgumentException("A document must have at least one page to batch");
        }
        int chunkSize = sarvamProperties.pipeline().ocrMaxPagesPerChunk();

        List<OcrBatch> batches = new ArrayList<>();
        for (int start = 1; start <= totalPages; start += chunkSize) {
            int end = Math.min(start + chunkSize - 1, totalPages);
            PageRange range = new PageRange(start, end);
            batches.add(new OcrBatch(documentId, range.format()));
        }
        return batches;
    }
}
