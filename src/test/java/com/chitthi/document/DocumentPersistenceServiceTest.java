package com.chitthi.document;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.service.DocumentPersistenceService;
import com.chitthi.ocr.event.OcrBatchCreatedEvent;
import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.repository.OcrBatchRepository;
import com.chitthi.ocr.service.OcrBatchPlanner;
import com.chitthi.progress.DocumentProgressEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentPersistenceServiceTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final PageRepository pageRepository = mock(PageRepository.class);
    private final OcrBatchRepository ocrBatchRepository = mock(OcrBatchRepository.class);
    private final OcrBatchPlanner ocrBatchPlanner = mock(OcrBatchPlanner.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final DocumentPersistenceService persistenceService = new DocumentPersistenceService(
            documentRepository, pageRepository, ocrBatchRepository, ocrBatchPlanner, eventPublisher);

    @Test
    void persistPages_savesPagesBatchesAndMarksDocumentProcessing() {
        Document document = new Document("user", "title", "hi", null, null);
        UUID documentId = UUID.randomUUID();
        setId(document, documentId);
        List<Page> pages = List.of(new Page(documentId, 1, "k1"), new Page(documentId, 2, "k2"));
        OcrBatch batch = new OcrBatch(documentId, "1-2");
        when(ocrBatchPlanner.plan(any(), anyInt())).thenReturn(List.of(batch));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        persistenceService.persistPages(document, pages);

        verify(pageRepository).saveAll(pages);
        verify(ocrBatchRepository).saveAll(List.of(batch));
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        verify(documentRepository).save(document);

        verify(eventPublisher, times(1)).publishEvent(any(OcrBatchCreatedEvent.class));
        verify(eventPublisher).publishEvent(new DocumentProgressEvent(documentId));
    }

    @Test
    void persistPages_publishesOneEventPerBatch() {
        Document document = new Document("user", "title", "hi", null, null);
        UUID documentId = UUID.randomUUID();
        setId(document, documentId);
        List<Page> pages = List.of(new Page(documentId, 1, "k1"));
        OcrBatch first = new OcrBatch(documentId, "1-10");
        OcrBatch second = new OcrBatch(documentId, "11-12");
        when(ocrBatchPlanner.plan(any(), anyInt())).thenReturn(List.of(first, second));
        when(documentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        persistenceService.persistPages(document, pages);

        verify(eventPublisher, times(2)).publishEvent(any(OcrBatchCreatedEvent.class));
    }

    private void setId(Document document, UUID id) {
        try {
            var field = Document.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(document, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
