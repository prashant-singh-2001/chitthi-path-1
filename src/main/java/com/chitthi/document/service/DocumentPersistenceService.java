package com.chitthi.document.service;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.Page;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.repository.OcrBatchRepository;
import com.chitthi.ocr.service.OcrBatchPlanner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Owns the one transactional step of an upload: writing the page rows and
 * planning the OCR batches for them. Kept as its own bean, separate from
 * {@link DocumentUploadService}, because {@code @Transactional} is applied
 * via a Spring proxy — a method calling another {@code @Transactional}
 * method on {@code this} bypasses that proxy and silently runs without a
 * transaction. Routing the call through a different bean is the
 * straightforward way to avoid that pitfall.
 *
 * <p>Batch rows are created here but nothing is published to the OCR queue
 * yet — that happens after this transaction commits, once the pipeline queue
 * lands, so a rolled-back upload never queues work.
 */
@Service
public class DocumentPersistenceService {

    private final PageRepository pageRepository;
    private final OcrBatchRepository ocrBatchRepository;
    private final OcrBatchPlanner ocrBatchPlanner;

    public DocumentPersistenceService(PageRepository pageRepository,
                                       OcrBatchRepository ocrBatchRepository,
                                       OcrBatchPlanner ocrBatchPlanner) {
        this.pageRepository = pageRepository;
        this.ocrBatchRepository = ocrBatchRepository;
        this.ocrBatchPlanner = ocrBatchPlanner;
    }

    @Transactional
    public void persistPages(Document document, List<Page> pages) {
        pageRepository.saveAll(pages);

        List<OcrBatch> batches = ocrBatchPlanner.plan(document.getId(), pages.size());
        ocrBatchRepository.saveAll(batches);
    }
}
