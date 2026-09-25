package com.chitthi.assemble;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.progress.DocumentProgressEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssembleStateServiceTest {

    private final PageRepository pageRepository = mock(PageRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final AssembleStateService stateService =
            new AssembleStateService(pageRepository, documentRepository, eventPublisher);

    @Test
    void promotesAudioDonePagesToIndexedAndCompletesTheDocumentWhenNoneFailed() {
        UUID documentId = UUID.randomUUID();
        Page audioDone = new Page(documentId, 1, "k1");
        audioDone.setStatus(PageStatus.AUDIO_DONE);
        Page alreadyIndexed = new Page(documentId, 2, "k2");
        alreadyIndexed.setStatus(PageStatus.INDEXED);
        when(pageRepository.findByDocumentIdOrderByPageNo(documentId)).thenReturn(List.of(audioDone, alreadyIndexed));
        Document document = new Document("owner", "title", "hi", null, null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        stateService.finalizeDocument(documentId, false);

        assertThat(audioDone.getStatus()).isEqualTo(PageStatus.INDEXED);
        assertThat(alreadyIndexed.getStatus()).isEqualTo(PageStatus.INDEXED);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.COMPLETE);
        verify(eventPublisher).publishEvent(new DocumentProgressEvent(documentId));
    }

    @Test
    void marksTheDocumentPartialWhenAnyPageFailed() {
        UUID documentId = UUID.randomUUID();
        Page audioDone = new Page(documentId, 1, "k1");
        audioDone.setStatus(PageStatus.AUDIO_DONE);
        when(pageRepository.findByDocumentIdOrderByPageNo(documentId)).thenReturn(List.of(audioDone));
        Document document = new Document("owner", "title", "hi", null, null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        stateService.finalizeDocument(documentId, true);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PARTIAL);
    }
}
