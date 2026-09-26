package com.chitthi.document.service;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.messaging.outbox.OutboxService;
import com.chitthi.progress.DocumentProgressEvent;
import com.chitthi.translate.message.TranslateMessage;
import com.chitthi.tts.message.TtsMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * FR7's manual retry: re-queues every FAILED page of a document from
 * whichever stage it fell out of. {@code stage_task} rows already DONE for
 * that page's chunks are reused by {@link com.chitthi.translate.TranslateWorker}
 * / {@link com.chitthi.tts.TtsWorker} without a second paid call - only the
 * chunks that never finished actually re-run.
 */
@Service
public class DocumentRetryService {

    /** A page whose {@code original_text} is null failed OCR at the batch level, not per-page - resubmitting its
     * whole batch would re-pay for pages that already succeeded, so it's left for a future batch-level retry. */
    public record RetryResult(int requeued, int skipped) {
    }

    private final DocumentRepository documentRepository;
    private final PageRepository pageRepository;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    public DocumentRetryService(DocumentRepository documentRepository, PageRepository pageRepository,
                                 OutboxService outboxService, ApplicationEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public RetryResult retryFailedPages(UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        List<Page> pages = pageRepository.findByDocumentIdOrderByPageNo(documentId);

        int requeued = 0;
        int skipped = 0;
        for (Page page : pages) {
            if (page.getStatus() != PageStatus.FAILED) {
                continue;
            }
            if (page.getOriginalText() == null) {
                skipped++;
                continue;
            }
            if (page.getTranslatedText() == null) {
                page.setStatus(PageStatus.OCR_DONE);
                outboxService.enqueue(PipelineQueues.TRANSLATE_QUEUE, new TranslateMessage(page.getId()));
            } else {
                page.setStatus(PageStatus.TRANSLATED);
                outboxService.enqueue(PipelineQueues.TTS_QUEUE, new TtsMessage(page.getId()));
            }
            requeued++;
        }
        pageRepository.saveAll(pages);

        if (requeued > 0) {
            document.setStatus(DocumentStatus.PROCESSING);
            documentRepository.save(document);
            eventPublisher.publishEvent(new DocumentProgressEvent(documentId));
        }

        return new RetryResult(requeued, skipped);
    }
}
