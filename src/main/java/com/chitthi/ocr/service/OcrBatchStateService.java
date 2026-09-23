package com.chitthi.ocr.service;

import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.repository.OcrBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Every short transaction the OCR worker and status poller need against
 * {@code ocr_batch}, kept out of both so neither ever holds a database
 * transaction open across a Sarvam HTTP call or a MinIO read.
 */
@Service
public class OcrBatchStateService {

    private final OcrBatchRepository ocrBatchRepository;

    public OcrBatchStateService(OcrBatchRepository ocrBatchRepository) {
        this.ocrBatchRepository = ocrBatchRepository;
    }

    /**
     * Claims a batch for submission via {@link OcrBatchRepository#claimForSubmit},
     * a single atomic UPDATE - only the caller whose UPDATE actually affects a
     * row gets a non-empty result back, so two workers (or a redelivered
     * message racing the poller's redispatch sweep) can never both believe
     * they claimed the same batch.
     */
    @Transactional
    public Optional<OcrBatch> claimForSubmit(UUID batchId, int maxSubmitAttempts) {
        int claimed = ocrBatchRepository.claimForSubmit(batchId, maxSubmitAttempts);
        if (claimed == 0) {
            return Optional.empty();
        }
        return ocrBatchRepository.findById(batchId);
    }

    @Transactional
    public void recordJobId(UUID batchId, String sarvamJobId, OffsetDateTime nextPollAt) {
        ocrBatchRepository.findById(batchId).ifPresent(batch -> {
            batch.setSarvamJobId(sarvamJobId);
            batch.setNextPollAt(nextPollAt);
        });
    }

    @Transactional
    public void recordSubmitFailure(UUID batchId, String errorMessage) {
        ocrBatchRepository.findById(batchId).ifPresent(batch -> batch.setLastError(truncate(errorMessage)));
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
