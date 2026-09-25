package com.chitthi.messaging;

import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.progress.DocumentProgressEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineFailureStateServiceTest {

    private final PageRepository pageRepository = mock(PageRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final PipelineFailureStateService stateService =
            new PipelineFailureStateService(pageRepository, eventPublisher);

    @Test
    void marksThePageFailedAndReturnsItsDocumentId() {
        UUID documentId = UUID.randomUUID();
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.TRANSLATED);
        when(pageRepository.findById(page.getId())).thenReturn(Optional.of(page));

        Optional<UUID> result = stateService.markPageFailed(page.getId());

        assertThat(result).contains(documentId);
        assertThat(page.getStatus()).isEqualTo(PageStatus.FAILED);
        verify(eventPublisher).publishEvent(new DocumentProgressEvent(documentId));
    }

    @Test
    void leavesAnAlreadyIndexedPageAlone() {
        UUID documentId = UUID.randomUUID();
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.INDEXED);
        when(pageRepository.findById(page.getId())).thenReturn(Optional.of(page));

        stateService.markPageFailed(page.getId());

        assertThat(page.getStatus()).isEqualTo(PageStatus.INDEXED);
        verify(pageRepository, never()).save(page);
        verify(eventPublisher, never()).publishEvent(new DocumentProgressEvent(documentId));
    }

    @Test
    void returnsEmptyWhenThePageNoLongerExists() {
        UUID pageId = UUID.randomUUID();
        when(pageRepository.findById(pageId)).thenReturn(Optional.empty());

        Optional<UUID> result = stateService.markPageFailed(pageId);

        assertThat(result).isEmpty();
    }
}
