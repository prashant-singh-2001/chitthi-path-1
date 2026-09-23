package com.chitthi.ocr.service;

import com.chitthi.document.model.Document;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.model.OcrBatchStatus;
import com.chitthi.ocr.repository.OcrBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final DocumentRepository documentRepository;
    private final OcrPollSchedule pollSchedule;

    public OcrBatchStateService(OcrBatchRepository ocrBatchRepository, DocumentRepository documentRepository,
                                 OcrPollSchedule pollSchedule) {
        this.ocrBatchRepository = ocrBatchRepository;
        this.documentRepository = documentRepository;
        this.pollSchedule = pollSchedule;
    }

    /** A batch selected for a status poll, carrying just enough to make the call without a second query. */
    public record ClaimedForPoll(UUID batchId, UUID documentId, String pageRange, String sarvamJobId, int pollCount) {
    }

    /** A batch selected for redispatch: its dispatch lease elapsed with no Sarvam job ever recorded. */
    public record ClaimedForRedispatch(UUID batchId, UUID documentId, String language, String pageRange) {
    }

    /**
     * Locks up to {@code limit} due batches and immediately advances each
     * one's {@code next_poll_at} by {@link OcrPollSchedule}'s backoff curve,
     * releasing the lock on commit - before any of them is actually polled.
     * Advancing it here, unconditionally, is what makes a poll attempt
     * "used" regardless of what Sarvam returns: a batch that comes back
     * PENDING/RUNNING again still moves forward on the curve rather than
     * being immediately due again next tick. See
     * {@link OcrBatchRepository#lockDueForPoll}.
     */
    @Transactional
    public List<ClaimedForPoll> claimDueForPoll(int limit) {
        List<OcrBatch> due = ocrBatchRepository.lockDueForPoll(limit);
        List<ClaimedForPoll> claimed = new ArrayList<>();
        for (OcrBatch batch : due) {
            int newPollCount = batch.getPollCount() + 1;
            batch.setPollCount(newPollCount);
            batch.setNextPollAt(OffsetDateTime.now().plus(pollSchedule.delayAfter(newPollCount)));
            claimed.add(new ClaimedForPoll(batch.getId(), batch.getDocumentId(), batch.getPageRange(),
                    batch.getSarvamJobId(), newPollCount));
        }
        return claimed;
    }

    /**
     * Locks stalled batches, resets them to PENDING, and gives them a fresh
     * dispatch lease - all before the caller republishes an
     * {@code OcrBatchMessage} for each one. See
     * {@link OcrBatchRepository#lockStalledForRedispatch}.
     */
    @Transactional
    public List<ClaimedForRedispatch> claimStalledForRedispatch(int maxSubmitAttempts, int limit, Duration newLease) {
        List<OcrBatch> stalled = ocrBatchRepository.lockStalledForRedispatch(maxSubmitAttempts, limit);
        if (stalled.isEmpty()) {
            return List.of();
        }
        OffsetDateTime lease = OffsetDateTime.now().plus(newLease);
        Map<UUID, String> languageByDocumentId = new HashMap<>();
        for (Document document : documentRepository.findAllById(
                stalled.stream().map(OcrBatch::getDocumentId).distinct().toList())) {
            languageByDocumentId.put(document.getId(), document.getLanguage());
        }

        List<ClaimedForRedispatch> claimed = new ArrayList<>();
        for (OcrBatch batch : stalled) {
            batch.setStatus(OcrBatchStatus.PENDING);
            batch.setNextPollAt(lease);
            claimed.add(new ClaimedForRedispatch(batch.getId(), batch.getDocumentId(),
                    languageByDocumentId.get(batch.getDocumentId()), batch.getPageRange()));
        }
        return claimed;
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
