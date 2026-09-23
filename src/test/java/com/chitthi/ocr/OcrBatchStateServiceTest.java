package com.chitthi.ocr;

import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.repository.OcrBatchRepository;
import com.chitthi.ocr.service.OcrBatchStateService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OcrBatchStateServiceTest {

    private final OcrBatchRepository ocrBatchRepository = mock(OcrBatchRepository.class);
    private final OcrBatchStateService stateService = new OcrBatchStateService(ocrBatchRepository);

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
}
