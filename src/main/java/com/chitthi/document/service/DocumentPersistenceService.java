package com.chitthi.document.service;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.messaging.outbox.OutboxService;
import com.chitthi.ocr.message.OcrBatchMessage;
import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.repository.OcrBatchRepository;
import com.chitthi.ocr.service.OcrBatchPlanner;
import com.chitthi.progress.DocumentProgressEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
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
 * <p>Each batch's {@link OcrBatchMessage} is enqueued via {@link OutboxService}
 * from inside this same transaction, so it only reaches {@code ocr.queue}
 * once this transaction actually commits, and a crash between commit and
 * publish can never strand it - see {@link com.chitthi.messaging.outbox.OutboxRelay}.
 */
@Service
public class DocumentPersistenceService {

    private final DocumentRepository documentRepository;
    private final PageRepository pageRepository;
    private final OcrBatchRepository ocrBatchRepository;
    private final OcrBatchPlanner ocrBatchPlanner;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentPersistenceService(DocumentRepository documentRepository,
                                       PageRepository pageRepository,
                                       OcrBatchRepository ocrBatchRepository,
                                       OcrBatchPlanner ocrBatchPlanner,
                                       OutboxService outboxService,
                                       ApplicationEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.ocrBatchRepository = ocrBatchRepository;
        this.ocrBatchPlanner = ocrBatchPlanner;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void persistPages(Document document, List<Page> pages) {
        pageRepository.saveAll(pages);

        List<OcrBatch> batches = ocrBatchPlanner.plan(document.getId(), pages.size());
        ocrBatchRepository.saveAll(batches);

        document.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(document);

        for (OcrBatch batch : batches) {
            outboxService.enqueue(PipelineQueues.OCR_QUEUE, new OcrBatchMessage(
                    batch.getId(), document.getId(), document.getLanguage(), batch.getPageRange()));
        }
        eventPublisher.publishEvent(new DocumentProgressEvent(document.getId()));
    }
}
