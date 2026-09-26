package com.chitthi.translate;

import com.chitthi.document.repository.PageRepository;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.messaging.outbox.OutboxService;
import com.chitthi.progress.DocumentProgressEvent;
import com.chitthi.tts.message.TtsMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The one transactional write of the translate stage, kept as its own bean
 * (not a method on {@link TranslateWorker}) for the same reason as
 * {@code DocumentPersistenceService}: a {@code @Transactional} method calling
 * another one on {@code this} bypasses the Spring proxy entirely.
 */
@Service
public class TranslateStateService {

    private final PageRepository pageRepository;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    public TranslateStateService(PageRepository pageRepository, OutboxService outboxService,
                                  ApplicationEventPublisher eventPublisher) {
        this.pageRepository = pageRepository;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @return true if the page was still OCR_DONE with the given text hash
     *         and is now TRANSLATED; false if another writer (a concurrent
     *         redelivery, or Day 10's edit flow resetting the page) already
     *         moved it on, in which case this translation is discarded.
     */
    @Transactional
    public boolean markTranslated(UUID pageId, UUID documentId, String translatedText, String textHash) {
        int updated = pageRepository.markTranslated(pageId, translatedText, textHash);
        if (updated > 0) {
            outboxService.enqueue(PipelineQueues.TTS_QUEUE, new TtsMessage(pageId));
            eventPublisher.publishEvent(new DocumentProgressEvent(documentId));
        }
        return updated > 0;
    }
}
