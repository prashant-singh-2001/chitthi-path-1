package com.chitthi.ocr;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.model.OcrBatchStatus;
import com.chitthi.ocr.repository.OcrBatchRepository;
import com.chitthi.ocr.result.ParsedPage;
import com.chitthi.ocr.service.OcrResultApplier;
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

class OcrResultApplierTest {

    private final PageRepository pageRepository = mock(PageRepository.class);
    private final OcrBatchRepository ocrBatchRepository = mock(OcrBatchRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final OcrResultApplier applier =
            new OcrResultApplier(pageRepository, ocrBatchRepository, documentRepository, eventPublisher);

    @Test
    void applyParsedResult_marksEveryPageOcrDoneWhenAllTextIsRecovered() {
        UUID documentId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        OcrBatch batch = new OcrBatch(documentId, "1-2");
        when(ocrBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        List<Page> pages = List.of(new Page(documentId, 1, "k1"), new Page(documentId, 2, "k2"));
        when(pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNo(documentId, 1, 2)).thenReturn(pages);
        when(ocrBatchRepository.findByDocumentIdOrderByPageRange(documentId)).thenReturn(List.of(batch));

        applier.applyParsedResult(batchId, List.of(new ParsedPage(1, "page one"), new ParsedPage(2, "page two")));

        assertThat(pages).extracting(Page::getStatus).containsOnly(PageStatus.OCR_DONE);
        assertThat(pages.get(0).getOriginalText()).isEqualTo("page one");
        assertThat(pages.get(0).getTextHash()).hasSize(64);
        assertThat(batch.getStatus()).isEqualTo(OcrBatchStatus.COMPLETED);
        assertThat(batch.getNextPollAt()).isNull();
        verify(documentRepository, never()).findById(any());
        verify(eventPublisher, org.mockito.Mockito.times(2))
                .publishEvent(org.mockito.ArgumentMatchers.any(com.chitthi.ocr.event.PageOcrCompletedEvent.class));
    }

    @Test
    void applyParsedResult_marksMissingPagesFailedAndBatchPartiallyCompleted() {
        UUID documentId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        OcrBatch batch = new OcrBatch(documentId, "1-2");
        when(ocrBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        List<Page> pages = List.of(new Page(documentId, 1, "k1"), new Page(documentId, 2, "k2"));
        when(pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNo(documentId, 1, 2)).thenReturn(pages);
        when(ocrBatchRepository.findByDocumentIdOrderByPageRange(documentId)).thenReturn(List.of(batch));
        Document document = new Document("owner", "title", "hi", null, null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        // Only page 1's text was recovered - Sarvam's partially_completed case.
        applier.applyParsedResult(batchId, List.of(new ParsedPage(1, "page one")));

        assertThat(pages.get(0).getStatus()).isEqualTo(PageStatus.OCR_DONE);
        assertThat(pages.get(1).getStatus()).isEqualTo(PageStatus.FAILED);
        assertThat(batch.getStatus()).isEqualTo(OcrBatchStatus.PARTIALLY_COMPLETED);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PARTIAL);
        verify(eventPublisher, org.mockito.Mockito.times(1))
                .publishEvent(org.mockito.ArgumentMatchers.any(com.chitthi.ocr.event.PageOcrCompletedEvent.class));
    }

    @Test
    void applyParsedResult_absoluteMappingUsesTheBatchsOwnPageRangeNotChunkRelativeNumbers() {
        UUID documentId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        OcrBatch batch = new OcrBatch(documentId, "11-12");
        when(ocrBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        List<Page> pages = List.of(new Page(documentId, 11, "k11"), new Page(documentId, 12, "k12"));
        when(pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNo(documentId, 11, 12)).thenReturn(pages);
        when(ocrBatchRepository.findByDocumentIdOrderByPageRange(documentId)).thenReturn(List.of(batch));

        applier.applyParsedResult(batchId, List.of(new ParsedPage(1, "eleven"), new ParsedPage(2, "twelve")));

        assertThat(pages.get(0).getOriginalText()).isEqualTo("eleven");
        assertThat(pages.get(1).getOriginalText()).isEqualTo("twelve");
    }

    @Test
    void markBatchFailed_onlyTouchesPagesStillPending() {
        UUID documentId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        OcrBatch batch = new OcrBatch(documentId, "1-2");
        when(ocrBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        Page alreadyDone = new Page(documentId, 1, "k1");
        alreadyDone.setStatus(PageStatus.OCR_DONE);
        Page stillPending = new Page(documentId, 2, "k2");
        List<Page> pages = List.of(alreadyDone, stillPending);
        when(pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNo(documentId, 1, 2)).thenReturn(pages);
        when(ocrBatchRepository.findByDocumentIdOrderByPageRange(documentId)).thenReturn(List.of(batch));
        Document document = new Document("owner", "title", "hi", null, null);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));

        applier.markBatchFailed(batchId, "Sarvam job rejected");

        assertThat(alreadyDone.getStatus()).isEqualTo(PageStatus.OCR_DONE);
        assertThat(stillPending.getStatus()).isEqualTo(PageStatus.FAILED);
        assertThat(batch.getStatus()).isEqualTo(OcrBatchStatus.FAILED);
        assertThat(batch.getLastError()).isEqualTo("Sarvam job rejected");
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PARTIAL);
    }

    @Test
    void documentStatus_isUntouchedWhileOtherBatchesAreStillInFlight() {
        UUID documentId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        OcrBatch completedBatch = new OcrBatch(documentId, "1-1");
        OcrBatch stillRunningBatch = new OcrBatch(documentId, "2-2");
        when(ocrBatchRepository.findById(batchId)).thenReturn(Optional.of(completedBatch));
        List<Page> pages = List.of(new Page(documentId, 1, "k1"));
        when(pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNo(documentId, 1, 1)).thenReturn(pages);
        when(ocrBatchRepository.findByDocumentIdOrderByPageRange(documentId))
                .thenReturn(List.of(completedBatch, stillRunningBatch));

        applier.applyParsedResult(batchId, List.of(new ParsedPage(1, "page one")));

        verify(documentRepository, never()).findById(any());
    }
}
