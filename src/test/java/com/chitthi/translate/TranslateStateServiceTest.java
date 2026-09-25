package com.chitthi.translate;

import com.chitthi.document.repository.PageRepository;
import com.chitthi.translate.event.PageTranslatedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslateStateServiceTest {

    private final PageRepository pageRepository = mock(PageRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TranslateStateService stateService = new TranslateStateService(pageRepository, eventPublisher);

    @Test
    void publishesAnEventAndReturnsTrueWhenTheUpdateApplies() {
        UUID pageId = UUID.randomUUID();
        when(pageRepository.markTranslated(pageId, "translated", "hash")).thenReturn(1);

        boolean result = stateService.markTranslated(pageId, "translated", "hash");

        assertThat(result).isTrue();
        verify(eventPublisher).publishEvent(new PageTranslatedEvent(pageId));
    }

    @Test
    void publishesNoEventAndReturnsFalseWhenThePageAlreadyMoved() {
        UUID pageId = UUID.randomUUID();
        when(pageRepository.markTranslated(pageId, "translated", "hash")).thenReturn(0);

        boolean result = stateService.markTranslated(pageId, "translated", "hash");

        assertThat(result).isFalse();
        verify(eventPublisher, never()).publishEvent(new PageTranslatedEvent(pageId));
    }
}
