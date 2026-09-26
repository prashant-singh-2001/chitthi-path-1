package com.chitthi.translate;

import com.chitthi.document.repository.PageRepository;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.messaging.outbox.OutboxService;
import com.chitthi.progress.DocumentProgressEvent;
import com.chitthi.tts.message.TtsMessage;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslateStateServiceTest {

    private final PageRepository pageRepository = mock(PageRepository.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TranslateStateService stateService =
            new TranslateStateService(pageRepository, outboxService, eventPublisher);

    @Test
    void enqueuesTtsWorkAndReturnsTrueWhenTheUpdateApplies() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(pageRepository.markTranslated(pageId, "translated", "hash")).thenReturn(1);

        boolean result = stateService.markTranslated(pageId, documentId, "translated", "hash");

        assertThat(result).isTrue();
        verify(outboxService).enqueue(PipelineQueues.TTS_QUEUE, new TtsMessage(pageId));
        verify(eventPublisher).publishEvent(new DocumentProgressEvent(documentId));
    }

    @Test
    void enqueuesNothingAndReturnsFalseWhenThePageAlreadyMoved() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(pageRepository.markTranslated(pageId, "translated", "hash")).thenReturn(0);

        boolean result = stateService.markTranslated(pageId, documentId, "translated", "hash");

        assertThat(result).isFalse();
        verify(outboxService, never()).enqueue(anyString(), any());
        verify(eventPublisher, never()).publishEvent(new DocumentProgressEvent(documentId));
    }
}
