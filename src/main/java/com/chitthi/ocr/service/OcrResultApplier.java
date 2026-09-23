package com.chitthi.ocr.service;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.service.TextHasher;
import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.model.OcrBatchStatus;
import com.chitthi.ocr.model.PageRange;
import com.chitthi.ocr.repository.OcrBatchRepository;
import com.chitthi.ocr.result.ParsedPage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a batch's outcome - successful or not - back onto its pages, the
 * batch row itself, and (once every batch for a document is terminal) the
 * document's own status. This is the only place {@code page.original_text}
 * is written.
 */
@Service
public class OcrResultApplier {

    private final PageRepository pageRepository;
    private final OcrBatchRepository ocrBatchRepository;
    private final DocumentRepository documentRepository;

    public OcrResultApplier(PageRepository pageRepository, OcrBatchRepository ocrBatchRepository,
                             DocumentRepository documentRepository) {
        this.pageRepository = pageRepository;
        this.ocrBatchRepository = ocrBatchRepository;
        this.documentRepository = documentRepository;
    }

    /**
     * Applies a completed or partially-completed job's parsed pages. A page
     * present in {@code parsedPages} is marked {@link PageStatus#OCR_DONE};
     * one in the batch's range but missing from the result (Sarvam's
     * {@code partially_completed}, or a parse strategy that only recovered
     * some pages) is marked {@link PageStatus#FAILED} - it does not stay
     * PENDING forever with nothing explaining why.
     */
    @Transactional
    public void applyParsedResult(UUID batchId, List<ParsedPage> parsedPages) {
        OcrBatch batch = requireBatch(batchId);
        PageRange range = PageRange.parse(batch.getPageRange());
        List<Page> pages = pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNo(
                batch.getDocumentId(), range.firstPage(), range.lastPage());

        Map<Integer, String> textByAbsolutePage = new HashMap<>();
        for (ParsedPage parsedPage : parsedPages) {
            textByAbsolutePage.put(range.absolutePageNo(parsedPage.chunkRelativeIndex()), parsedPage.text());
        }

        boolean everyPageRecovered = true;
        for (Page page : pages) {
            String text = textByAbsolutePage.get(page.getPageNo());
            if (text != null && !text.isBlank()) {
                page.setOriginalText(text);
                page.setTextHash(TextHasher.sha256Hex(text));
                page.setStatus(PageStatus.OCR_DONE);
            } else {
                page.setStatus(PageStatus.FAILED);
                everyPageRecovered = false;
            }
        }
        pageRepository.saveAll(pages);

        batch.setStatus(everyPageRecovered ? OcrBatchStatus.COMPLETED : OcrBatchStatus.PARTIALLY_COMPLETED);
        batch.setNextPollAt(null);
        ocrBatchRepository.save(batch);

        refreshDocumentStatus(batch.getDocumentId());
    }

    /**
     * A batch that Sarvam itself rejected or failed, or one that exhausted
     * its poll-attempt budget without ever reaching a terminal Sarvam status.
     * Every page still PENDING in its range moves to FAILED - "still
     * PENDING" specifically so a page an earlier partial apply already
     * touched is left alone.
     */
    @Transactional
    public void markBatchFailed(UUID batchId, String reason) {
        OcrBatch batch = requireBatch(batchId);
        PageRange range = PageRange.parse(batch.getPageRange());
        List<Page> pages = pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNo(
                batch.getDocumentId(), range.firstPage(), range.lastPage());

        for (Page page : pages) {
            if (page.getStatus() == PageStatus.PENDING) {
                page.setStatus(PageStatus.FAILED);
            }
        }
        pageRepository.saveAll(pages);

        batch.setStatus(OcrBatchStatus.FAILED);
        batch.setLastError(reason);
        batch.setNextPollAt(null);
        ocrBatchRepository.save(batch);

        refreshDocumentStatus(batch.getDocumentId());
    }

    private OcrBatch requireBatch(UUID batchId) {
        return ocrBatchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalStateException("OCR batch not found: " + batchId));
    }

    /**
     * A document only leaves PROCESSING once every one of its batches has
     * reached a terminal state - COMPLETE is not decided here, since OCR is
     * not the pipeline's last stage; a page only reaches COMPLETE once it is
     * INDEXED, well past translation and TTS. PARTIAL is decided the moment
     * any batch fails or partially completes, matching the state machine's
     * "PARTIAL when some pages are dead-lettered".
     */
    private void refreshDocumentStatus(UUID documentId) {
        List<OcrBatch> batches = ocrBatchRepository.findByDocumentIdOrderByPageRange(documentId);
        boolean anyStillInFlight = batches.stream()
                .anyMatch(b -> b.getStatus() == OcrBatchStatus.PENDING || b.getStatus() == OcrBatchStatus.RUNNING);
        if (anyStillInFlight) {
            return;
        }

        boolean anyFailedOrPartial = batches.stream()
                .anyMatch(b -> b.getStatus() == OcrBatchStatus.FAILED || b.getStatus() == OcrBatchStatus.PARTIALLY_COMPLETED);
        if (!anyFailedOrPartial) {
            return;
        }

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + documentId));
        document.setStatus(DocumentStatus.PARTIAL);
        documentRepository.save(document);
    }
}
