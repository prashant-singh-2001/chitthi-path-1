package com.chitthi.tts;

import com.chitthi.document.repository.PageRepository;
import com.chitthi.tts.event.PageAudioDoneEvent;
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
    private final ApplicationEventPublisher eventPublisher;

    public TtsStateService(PageRepository pageRepository, ApplicationEventPublisher eventPublisher) {
        this.pageRepository = pageRepository;
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
            eventPublisher.publishEvent(new PageAudioDoneEvent(documentId));
        }
        return updated > 0;
    }
}
