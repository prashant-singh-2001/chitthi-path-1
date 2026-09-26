package com.chitthi.ocr;

import com.chitthi.document.model.Document;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.messaging.outbox.OutboxService;
import com.chitthi.ocr.message.OcrBatchMessage;
import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.model.OcrBatchStatus;
import com.chitthi.ocr.repository.OcrBatchRepository;
import com.chitthi.ocr.service.OcrBatchStateService;
import com.chitthi.ocr.service.OcrPollSchedule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcrBatchStateServiceTest {

    private final OcrBatchRepository ocrBatchRepository = mock(OcrBatchRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final OcrPollSchedule pollSchedule = mock(OcrPollSchedule.class);
    private final OutboxService outboxService = mock(OutboxService.class);
    private final OcrBatchStateService stateService =
            new OcrBatchStateService(ocrBatchRepository, documentRepository, pollSchedule, outboxService);

    @Test
    void claimForSubmit_returnsBatchWhenTheUpdateAffectsARow() {
        UUID batchId = UUID.randomUUID();
        OcrBatch batch = new OcrBatch(UUID.randomUUID(), "1-10");
        when(ocrBatchRepository.claimForSubmit(batchId, 3)).thenReturn(1);
        when(ocrBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));

        Optional<OcrBatch> claimed = stateService.claimForSubmit(batchId, 3);

        assertThat(claimed).contains(batch);
    }

    @Test
    void claimForSubmit_returnsEmptyWithoutRefetchingWhenTheUpdateAffectsNoRow() {
        UUID batchId = UUID.randomUUID();
        when(ocrBatchRepository.claimForSubmit(batchId, 3)).thenReturn(0);

        Optional<OcrBatch> claimed = stateService.claimForSubmit(batchId, 3);

        assertThat(claimed).isEmpty();
        // Losing the claim must not cost an extra query - and more
        // importantly, must never fall back to reading the row as if it had won.
        verify(ocrBatchRepository, never()).findById(any());
    }

    @Test
    void recordJobId_setsJobIdAndNextPollOnTheExistingBatch() {
        UUID batchId = UUID.randomUUID();
        OcrBatch batch = new OcrBatch(UUID.randomUUID(), "1-10");
        when(ocrBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        OffsetDateTime nextPoll = OffsetDateTime.now().plusSeconds(5);

        stateService.recordJobId(batchId, "job-123", nextPoll);

        assertThat(batch.getSarvamJobId()).isEqualTo("job-123");
        assertThat(batch.getNextPollAt()).isEqualTo(nextPoll);
    }

    @Test
    void recordSubmitFailure_truncatesLongMessages() {
        UUID batchId = UUID.randomUUID();
        OcrBatch batch = new OcrBatch(UUID.randomUUID(), "1-10");
        when(ocrBatchRepository.findById(batchId)).thenReturn(Optional.of(batch));
        String longMessage = "x".repeat(3000);

        stateService.recordSubmitFailure(batchId, longMessage);

        assertThat(batch.getLastError()).hasSize(2000);
    }

    @Test
    void claimDueForPoll_incrementsPollCountAndAdvancesNextPollAtByTheBackoffCurve() {
        OcrBatch batch = new OcrBatch(UUID.randomUUID(), "1-10");
        batch.setSarvamJobId("job-123");
        when(ocrBatchRepository.lockDueForPoll(anyInt())).thenReturn(List.of(batch));
        when(pollSchedule.delayAfter(1)).thenReturn(Duration.ofSeconds(30));

        List<OcrBatchStateService.ClaimedForPoll> claimed = stateService.claimDueForPoll(20);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).sarvamJobId()).isEqualTo("job-123");
        assertThat(claimed.get(0).pollCount()).isEqualTo(1);
        assertThat(batch.getPollCount()).isEqualTo(1);
        assertThat(batch.getNextPollAt()).isAfter(OffsetDateTime.now());
    }

    @Test
    void claimStalledForRedispatch_resetsToPendingAndLooksUpTheDocumentsLanguage() {
        UUID documentId = UUID.randomUUID();
        OcrBatch batch = new OcrBatch(documentId, "1-10");
        Document document = new Document("owner", "title", "gu", null, null);
        when(ocrBatchRepository.lockStalledForRedispatch(anyInt(), anyInt())).thenReturn(List.of(batch));
        when(documentRepository.findAllById(any())).thenReturn(List.of(document));
        setDocumentId(document, documentId);

        List<OcrBatchStateService.ClaimedForRedispatch> claimed =
                stateService.claimStalledForRedispatch(3, 20, Duration.ofSeconds(60));

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).language()).isEqualTo("gu");
        assertThat(batch.getStatus()).isEqualTo(OcrBatchStatus.PENDING);
        assertThat(batch.getNextPollAt()).isAfter(OffsetDateTime.now());
        verify(outboxService).enqueue(org.mockito.ArgumentMatchers.eq(PipelineQueues.OCR_QUEUE), any(OcrBatchMessage.class));
    }

    @Test
    void claimStalledForRedispatch_returnsEmptyWithoutLookingUpDocumentsWhenNothingIsStalled() {
        when(ocrBatchRepository.lockStalledForRedispatch(anyInt(), anyInt())).thenReturn(List.of());

        List<OcrBatchStateService.ClaimedForRedispatch> claimed =
                stateService.claimStalledForRedispatch(3, 20, Duration.ofSeconds(60));

        assertThat(claimed).isEmpty();
        verify(documentRepository, never()).findAllById(any());
    }

    private void setDocumentId(Document document, UUID id) {
        try {
            var field = Document.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(document, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
