package com.chitthi.document.service;

import com.chitthi.cap.WordBudgetService;
import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.messaging.outbox.OutboxService;
import com.chitthi.progress.DocumentProgressEvent;
import com.chitthi.text.WordCounter;
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
 *
 * <p>FR15: also re-queues CAPPED pages, re-claiming the owner's daily word
 * budget on the way - this is the "resume tomorrow" path for a page that
 * stopped because of the cap, with no new scheduler needed. A page still
 * over budget stays CAPPED and counts as skipped, exactly like a page that
 * never recovered any OCR text.
 */
@Service
public class DocumentRetryService {

    /** A page counts as skipped either because its {@code original_text} is null (it failed OCR at the batch
     * level, not per-page - resubmitting its whole batch would re-pay for pages that already succeeded, so it's
     * left for a future batch-level retry) or because it is still CAPPED and the owner's budget is still spent. */
    public record RetryResult(int requeued, int skipped) {
    }

    private final DocumentRepository documentRepository;
    private final PageRepository pageRepository;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;
    private final WordBudgetService wordBudgetService;

    public DocumentRetryService(DocumentRepository documentRepository, PageRepository pageRepository,
                                 OutboxService outboxService, ApplicationEventPublisher eventPublisher,
                                 WordBudgetService wordBudgetService) {
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
        this.wordBudgetService = wordBudgetService;
    }

    @Transactional
    public RetryResult retryFailedPages(UUID documentId, String ownerId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!document.getOwnerId().equals(ownerId)) {
            // FR14: 404, not 403 - see PageEditService.editText's identical check.
            throw new DocumentNotFoundException(documentId);
        }
        List<Page> pages = pageRepository.findByDocumentIdOrderByPageNo(documentId);

        int requeued = 0;
        int skipped = 0;
        for (Page page : pages) {
            if (page.getStatus() == PageStatus.CAPPED) {
                if (!wordBudgetService.tryClaim(document.getOwnerId(), WordCounter.count(page.getOriginalText()))) {
                    skipped++;
                    continue;
                }
                page.setStatus(PageStatus.OCR_DONE);
                outboxService.enqueue(PipelineQueues.TRANSLATE_QUEUE, new TranslateMessage(page.getId()));
                requeued++;
                continue;
            }
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
