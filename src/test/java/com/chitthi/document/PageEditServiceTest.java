package com.chitthi.document;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.service.DocumentNotFoundException;
import com.chitthi.document.service.InvalidPageTextException;
import com.chitthi.document.service.PageEditService;
import com.chitthi.document.service.PageNotFoundException;
import com.chitthi.document.service.PageNotReadyException;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.messaging.outbox.OutboxService;
import com.chitthi.translate.message.TranslateMessage;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageEditServiceTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final PageRepository pageRepository = mock(PageRepository.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final PageEditService editService =
            new PageEditService(documentRepository, pageRepository, outboxService, eventPublisher);

    @Test
    void anIndexedPageIsResetToOcrDoneAndTranslationIsEnqueued() {
        UUID documentId = UUID.randomUUID();
        Document document = new Document("owner", "title", "hi", null, null);
        Page page = new Page(documentId, 3, "k3");
        page.setStatus(PageStatus.INDEXED);
        page.setOriginalText("old text");
        page.setTextHash("old-hash");
        page.setTranslatedText("translated");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(pageRepository.findByDocumentIdAndPageNo(documentId, 3)).thenReturn(Optional.of(page));

        Page result = editService.editText(documentId, 3, "corrected text");

        assertThat(result.getStatus()).isEqualTo(PageStatus.OCR_DONE);
        assertThat(result.getOriginalText()).isEqualTo("corrected text");
        assertThat(result.isEdited()).isTrue();
        assertThat(result.getTranslatedText()).isNull();
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        verify(outboxService).enqueue(PipelineQueues.TRANSLATE_QUEUE, new TranslateMessage(page.getId()));
    }

    @Test
    void savingTheSameTextIsANoOp() {
        UUID documentId = UUID.randomUUID();
        Document document = new Document("owner", "title", "hi", null, null);
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.INDEXED);
        page.setOriginalText("same text");
        String hash = com.chitthi.document.service.TextHasher.sha256Hex("same text");
        page.setTextHash(hash);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(pageRepository.findByDocumentIdAndPageNo(documentId, 1)).thenReturn(Optional.of(page));

        Page result = editService.editText(documentId, 1, "same text");

        assertThat(result.getStatus()).isEqualTo(PageStatus.INDEXED);
        verify(pageRepository, never()).save(any());
        verify(outboxService, never()).enqueue(any(), any());
        assertThat(document.getStatus()).isNotEqualTo(DocumentStatus.PROCESSING);
    }

    @Test
    void aPendingPageCannotBeEdited() {
        UUID documentId = UUID.randomUUID();
        Document document = new Document("owner", "title", "hi", null, null);
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.PENDING);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(pageRepository.findByDocumentIdAndPageNo(documentId, 1)).thenReturn(Optional.of(page));

        assertThatThrownBy(() -> editService.editText(documentId, 1, "some text"))
                .isInstanceOf(PageNotReadyException.class);
        verify(outboxService, never()).enqueue(any(), any());
    }

    @Test
    void aFailedPageWithNoOcrTextCanBeEditedAsItsRecoveryPath() {
        UUID documentId = UUID.randomUUID();
        Document document = new Document("owner", "title", "hi", null, null);
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.FAILED);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(pageRepository.findByDocumentIdAndPageNo(documentId, 1)).thenReturn(Optional.of(page));

        Page result = editService.editText(documentId, 1, "typed in by hand");

        assertThat(result.getStatus()).isEqualTo(PageStatus.OCR_DONE);
        assertThat(result.getOriginalText()).isEqualTo("typed in by hand");
        verify(outboxService).enqueue(PipelineQueues.TRANSLATE_QUEUE, new TranslateMessage(page.getId()));
    }

    @Test
    void blankTextIsRejected() {
        UUID documentId = UUID.randomUUID();
        Document document = new Document("owner", "title", "hi", null, null);
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.INDEXED);
        page.setOriginalText("existing");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(pageRepository.findByDocumentIdAndPageNo(documentId, 1)).thenReturn(Optional.of(page));

        assertThatThrownBy(() -> editService.editText(documentId, 1, "   "))
                .isInstanceOf(InvalidPageTextException.class);
    }

    @Test
    void tooLongTextIsRejected() {
        UUID documentId = UUID.randomUUID();
        Document document = new Document("owner", "title", "hi", null, null);
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.INDEXED);
        page.setOriginalText("existing");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(pageRepository.findByDocumentIdAndPageNo(documentId, 1)).thenReturn(Optional.of(page));

        assertThatThrownBy(() -> editService.editText(documentId, 1, "x".repeat(20_001)))
                .isInstanceOf(InvalidPageTextException.class);
    }

    @Test
    void aMissingDocumentIsRejected() {
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> editService.editText(documentId, 1, "text"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void aMissingPageIsRejected() {
        UUID documentId = UUID.randomUUID();
        Document document = new Document("owner", "title", "hi", null, null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(pageRepository.findByDocumentIdAndPageNo(documentId, 99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> editService.editText(documentId, 99, "text"))
                .isInstanceOf(PageNotFoundException.class);
    }
}
