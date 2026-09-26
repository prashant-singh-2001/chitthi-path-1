package com.chitthi.messaging;

import com.chitthi.assemble.message.AssembleMessage;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.messaging.outbox.OutboxService;
import com.chitthi.progress.DocumentProgressEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The one transactional write {@link PipelineMessageRecoverer} needs: moving
 * a page whose translate or TTS message exhausted retries to FAILED, so it
 * does not sit at OCR_DONE/TRANSLATED forever with nothing explaining why,
 * and enqueuing the assemble check that lets the document settle to PARTIAL
 * instead of waiting forever on a page that will never finish.
 */
@Service
public class PipelineFailureStateService {

    private final PageRepository pageRepository;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    public PipelineFailureStateService(PageRepository pageRepository, OutboxService outboxService,
                                        ApplicationEventPublisher eventPublisher) {
        this.pageRepository = pageRepository;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @return the page's document id, so the caller can log accordingly -
     *         empty if the page no longer exists.
     */
    @Transactional
    public Optional<UUID> markPageFailed(UUID pageId) {
        return pageRepository.findById(pageId).map(page -> {
            if (page.getStatus() != PageStatus.INDEXED) {
                page.setStatus(PageStatus.FAILED);
                pageRepository.save(page);
                outboxService.enqueue(PipelineQueues.ASSEMBLE_QUEUE, new AssembleMessage(page.getDocumentId()));
                eventPublisher.publishEvent(new DocumentProgressEvent(page.getDocumentId()));
            }
            return page.getDocumentId();
        });
    }
}
