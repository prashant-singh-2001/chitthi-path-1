package com.chitthi.document;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.service.DocumentRetryService;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.messaging.outbox.OutboxService;
import com.chitthi.translate.message.TranslateMessage;
import com.chitthi.tts.message.TtsMessage;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentRetryServiceTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final PageRepository pageRepository = mock(PageRepository.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final DocumentRetryService retryService =
            new DocumentRetryService(documentRepository, pageRepository, outboxService, eventPublisher);

    @Test
    void aFailedPageWithNoTranslationGoesBackToOcrDoneAndQueuesTranslation() {
        UUID documentId = UUID.randomUUID();
        Document document = new Document("owner", "title", "hi", null, null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.FAILED);
        page.setOriginalText("recovered text");
        when(pageRepository.findByDocumentIdOrderByPageNo(documentId)).thenReturn(List.of(page));

        DocumentRetryService.RetryResult result = retryService.retryFailedPages(documentId);

        assertThat(result.requeued()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(page.getStatus()).isEqualTo(PageStatus.OCR_DONE);
        verify(outboxService).enqueue(PipelineQueues.TRANSLATE_QUEUE, new TranslateMessage(page.getId()));
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
    }

    @Test
    void aFailedPageAlreadyTranslatedGoesBackToTranslatedAndQueuesTts() {
        UUID documentId = UUID.randomUUID();
        Document document = new Document("owner", "title", "hi", null, null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.FAILED);
        page.setOriginalText("original");
        page.setTranslatedText("translated");
        when(pageRepository.findByDocumentIdOrderByPageNo(documentId)).thenReturn(List.of(page));

        DocumentRetryService.RetryResult result = retryService.retryFailedPages(documentId);

        assertThat(result.requeued()).isEqualTo(1);
        assertThat(page.getStatus()).isEqualTo(PageStatus.TRANSLATED);
        verify(outboxService).enqueue(PipelineQueues.TTS_QUEUE, new TtsMessage(page.getId()));
    }

    @Test
    void aFailedPageWithNoOriginalTextIsSkippedNotRequeued() {
        UUID documentId = UUID.randomUUID();
        Document document = new Document("owner", "title", "hi", null, null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.FAILED);
        when(pageRepository.findByDocumentIdOrderByPageNo(documentId)).thenReturn(List.of(page));

        DocumentRetryService.RetryResult result = retryService.retryFailedPages(documentId);

        assertThat(result.requeued()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(page.getStatus()).isEqualTo(PageStatus.FAILED);
        verify(outboxService, never()).enqueue(any(), any());
        assertThat(document.getStatus()).isNotEqualTo(DocumentStatus.PROCESSING);
    }

    @Test
    void nonFailedPagesAreLeftAlone() {
        UUID documentId = UUID.randomUUID();
        Document document = new Document("owner", "title", "hi", null, null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        Page indexed = new Page(documentId, 1, "k1");
        indexed.setStatus(PageStatus.INDEXED);
        when(pageRepository.findByDocumentIdOrderByPageNo(documentId)).thenReturn(List.of(indexed));

        DocumentRetryService.RetryResult result = retryService.retryFailedPages(documentId);

        assertThat(result.requeued()).isZero();
        assertThat(result.skipped()).isZero();
        verify(outboxService, never()).enqueue(any(), any());
    }
}
