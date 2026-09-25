package com.chitthi.tts;

import com.chitthi.document.repository.PageRepository;
import com.chitthi.progress.DocumentProgressEvent;
import com.chitthi.tts.event.PageAudioDoneEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TtsStateServiceTest {

    private final PageRepository pageRepository = mock(PageRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TtsStateService stateService = new TtsStateService(pageRepository, eventPublisher);

    @Test
    void publishesADocumentScopedEventAndReturnsTrueWhenTheUpdateApplies() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(pageRepository.markAudioDone(pageId)).thenReturn(1);

        boolean result = stateService.markAudioDone(pageId, documentId);

        assertThat(result).isTrue();
        verify(eventPublisher).publishEvent(new PageAudioDoneEvent(documentId));
        verify(eventPublisher).publishEvent(new DocumentProgressEvent(documentId));
    }

    @Test
    void publishesNoEventAndReturnsFalseWhenThePageAlreadyMoved() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(pageRepository.markAudioDone(pageId)).thenReturn(0);

        boolean result = stateService.markAudioDone(pageId, documentId);

        assertThat(result).isFalse();
        verify(eventPublisher, never()).publishEvent(new PageAudioDoneEvent(documentId));
        verify(eventPublisher, never()).publishEvent(new DocumentProgressEvent(documentId));
    }
}
