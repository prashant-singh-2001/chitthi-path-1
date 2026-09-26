package com.chitthi.tts;

import com.chitthi.assemble.message.AssembleMessage;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.messaging.outbox.OutboxService;
import com.chitthi.progress.DocumentProgressEvent;
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

class TtsStateServiceTest {

    private final PageRepository pageRepository = mock(PageRepository.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TtsStateService stateService = new TtsStateService(pageRepository, outboxService, eventPublisher);

    @Test
    void enqueuesAnAssembleCheckAndReturnsTrueWhenTheUpdateApplies() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(pageRepository.markAudioDone(pageId)).thenReturn(1);

        boolean result = stateService.markAudioDone(pageId, documentId);

        assertThat(result).isTrue();
        verify(outboxService).enqueue(PipelineQueues.ASSEMBLE_QUEUE, new AssembleMessage(documentId));
        verify(eventPublisher).publishEvent(new DocumentProgressEvent(documentId));
    }

    @Test
    void enqueuesNothingAndReturnsFalseWhenThePageAlreadyMoved() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(pageRepository.markAudioDone(pageId)).thenReturn(0);

        boolean result = stateService.markAudioDone(pageId, documentId);

        assertThat(result).isFalse();
        verify(outboxService, never()).enqueue(anyString(), any());
        verify(eventPublisher, never()).publishEvent(new DocumentProgressEvent(documentId));
    }
}
