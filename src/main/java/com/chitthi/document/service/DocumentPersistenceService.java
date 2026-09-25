package com.chitthi.document.service;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.ocr.event.OcrBatchCreatedEvent;
import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.repository.OcrBatchRepository;
import com.chitthi.ocr.service.OcrBatchPlanner;
import com.chitthi.progress.DocumentProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Owns the one transactional step of an upload: writing the page rows,
 * planning the OCR batches for them, and marking the document as
 * {@link DocumentStatus#PROCESSING}. Kept as its own bean, separate from
 * {@link DocumentUploadService}, because {@code @Transactional} is applied
 * via a Spring proxy — a method calling another {@code @Transactional}
 * method on {@code this} bypasses that proxy and silently runs without a
 * transaction. Routing the call through a different bean is the
 * straightforward way to avoid that pitfall.
 *
 * <p>Each batch's {@link OcrBatchCreatedEvent} is published from inside this
 * transaction, but {@code com.chitthi.ocr.service.OcrDispatcher} only sends
 * it to the OCR queue after this transaction commits ({@code
 * @TransactionalEventListener(phase = AFTER_COMMIT)}), so a rolled-back
 * upload never queues work for pages that don't exist.
 */
@Service
public class DocumentPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(DocumentPersistenceService.class);

    private final DocumentRepository documentRepository;
    private final PageRepository pageRepository;
    private final OcrBatchRepository ocrBatchRepository;
    private final OcrBatchPlanner ocrBatchPlanner;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentPersistenceService(DocumentRepository documentRepository,
                                       PageRepository pageRepository,
                                       OcrBatchRepository ocrBatchRepository,
                                       OcrBatchPlanner ocrBatchPlanner,
                                       ApplicationEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.ocrBatchRepository = ocrBatchRepository;
        this.ocrBatchPlanner = ocrBatchPlanner;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void persistPages(Document document, List<Page> pages) {
        pageRepository.saveAll(pages);

        List<OcrBatch> batches = ocrBatchPlanner.plan(document.getId(), pages.size());
        ocrBatchRepository.saveAll(batches);

        document.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(document);

        // @TransactionalEventListener silently discards an event raised with
        // no active transaction (fallbackExecution defaults to false). This
        // method's own @Transactional guarantees one today; the check exists
        // so a future refactor that drops it fails loudly here instead of
        // OcrDispatcher never firing and OCR work quietly never starting.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("persistPages running without an active transaction; "
                    + "OcrBatchCreatedEvent for document {} will not be delivered", document.getId());
        }

        for (OcrBatch batch : batches) {
            eventPublisher.publishEvent(new OcrBatchCreatedEvent(
                    batch.getId(), document.getId(), document.getLanguage(), batch.getPageRange()));
        }
        eventPublisher.publishEvent(new DocumentProgressEvent(document.getId()));
    }
}
