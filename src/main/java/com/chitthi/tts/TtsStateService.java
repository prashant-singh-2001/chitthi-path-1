package com.chitthi.tts;

import com.chitthi.assemble.message.AssembleMessage;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.messaging.outbox.OutboxService;
import com.chitthi.progress.DocumentProgressEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The one transactional write of the TTS stage, kept as its own bean for the
 * same {@code @Transactional} self-invocation reason as
 * {@link com.chitthi.translate.TranslateStateService}.
 */
@Service
public class TtsStateService {

    private final PageRepository pageRepository;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    public TtsStateService(PageRepository pageRepository, OutboxService outboxService,
                            ApplicationEventPublisher eventPublisher) {
        this.pageRepository = pageRepository;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @return true if the page was still TRANSLATED and is now AUDIO_DONE;
     *         false if another writer already moved it on, in which case the
     *         audio this call just wrote is orphaned but harmless - it is
     *         never referenced by an assembled track.
     */
    @Transactional
    public boolean markAudioDone(UUID pageId, UUID documentId) {
        int updated = pageRepository.markAudioDone(pageId);
        if (updated > 0) {
            outboxService.enqueue(PipelineQueues.ASSEMBLE_QUEUE, new AssembleMessage(documentId));
            eventPublisher.publishEvent(new DocumentProgressEvent(documentId));
        }
        return updated > 0;
    }
}
