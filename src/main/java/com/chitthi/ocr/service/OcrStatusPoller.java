package com.chitthi.ocr.service;

import com.chitthi.ocr.OcrProperties;
import com.chitthi.ocr.result.DigitiseResultParser;
import com.chitthi.ocr.result.ParsedPage;
import com.chitthi.sarvam.SarvamClient;
import com.chitthi.sarvam.dto.DownloadUrlResponse;
import com.chitthi.sarvam.dto.JobStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Two independent sweeps, run back to back on one scheduler tick:
 * {@link #pollDueJobs} advances batches that already have a Sarvam job, and
 * {@link #redispatchStalledBatches} rescues ones that never got one. Neither
 * holds a database transaction across the Sarvam calls it makes -
 * {@link OcrBatchStateService} claims work in a short transaction first, this
 * class only touches the network and {@link OcrResultApplier} afterward.
 *
 * <p>{@code fixedDelay}, not {@code fixedRate}: a slow sweep (many batches
 * due at once) must never overlap itself on the default single-threaded
 * scheduler. {@code chitthi.ocr.poller.enabled} lets a test turn this off
 * entirely and drive {@link #sweep} by hand instead, for deterministic
 * assertions with no waiting.
 */
@Component
@ConditionalOnProperty(name = "chitthi.ocr.poller.enabled", matchIfMissing = true)
public class OcrStatusPoller {

    private static final Logger log = LoggerFactory.getLogger(OcrStatusPoller.class);

    private final OcrBatchStateService stateService;
    private final OcrResultApplier resultApplier;
    private final OcrPollSchedule pollSchedule;
    private final SarvamClient sarvamClient;
    private final DigitiseResultParser resultParser;
    private final OcrProperties ocrProperties;

    public OcrStatusPoller(OcrBatchStateService stateService, OcrResultApplier resultApplier,
                            OcrPollSchedule pollSchedule, SarvamClient sarvamClient,
                            DigitiseResultParser resultParser, OcrProperties ocrProperties) {
        this.stateService = stateService;
        this.resultApplier = resultApplier;
        this.pollSchedule = pollSchedule;
        this.sarvamClient = sarvamClient;
        this.resultParser = resultParser;
        this.ocrProperties = ocrProperties;
    }

    @Scheduled(fixedDelayString = "${chitthi.ocr.poller.sweep-interval-ms:1000}")
    public void sweep() {
        pollDueJobs();
        redispatchStalledBatches();
    }

    public void pollDueJobs() {
        List<OcrBatchStateService.ClaimedForPoll> due =
                stateService.claimDueForPoll(ocrProperties.poller().claimBatchSize());
        for (OcrBatchStateService.ClaimedForPoll batch : due) {
            pollOne(batch);
        }
    }

    private void pollOne(OcrBatchStateService.ClaimedForPoll batch) {
        MDC.put("documentId", batch.documentId().toString());
        MDC.put("batchId", batch.batchId().toString());
        try {
            JobStatusResponse status = sarvamClient.getJobStatus(batch.sarvamJobId());
            switch (status.status()) {
                case COMPLETED, PARTIALLY_COMPLETED -> applyResult(batch);
                case FAILED, REJECTED -> resultApplier.markBatchFailed(batch.batchId(),
                        "Sarvam job " + batch.sarvamJobId() + " ended as " + status.status());
                case PENDING, RUNNING -> checkGiveUp(batch);
            }
        } catch (RuntimeException e) {
            log.error("Failed to poll status for batch {} (job {})", batch.batchId(), batch.sarvamJobId(), e);
        } finally {
            MDC.remove("documentId");
            MDC.remove("batchId");
        }
    }

    private void checkGiveUp(OcrBatchStateService.ClaimedForPoll batch) {
        if (pollSchedule.hasExceededMaxAttempts(batch.pollCount())) {
            log.warn("Batch {} (job {}) did not complete within {} polls; giving up",
                    batch.batchId(), batch.sarvamJobId(), ocrProperties.poll().maxAttempts());
            resultApplier.markBatchFailed(batch.batchId(),
                    "Gave up after " + batch.pollCount() + " polls with no terminal Sarvam status");
        }
        // Otherwise leave it: claimDueForPoll already advanced next_poll_at
        // along OcrPollSchedule's backoff curve, so the next sweep to find
        // this batch due again is already scheduled correctly.
    }

    private void applyResult(OcrBatchStateService.ClaimedForPoll batch) {
        DownloadUrlResponse downloadUrl = sarvamClient.getDownloadUrl(batch.sarvamJobId());
        byte[] resultZip = sarvamClient.downloadResult(downloadUrl.downloadUrl());
        List<ParsedPage> parsedPages = resultParser.parse(resultZip,
                com.chitthi.ocr.model.PageRange.parse(batch.pageRange()));
        if (parsedPages.isEmpty()) {
            resultApplier.markBatchFailed(batch.batchId(), "No text could be recovered from the result ZIP");
        } else {
            resultApplier.applyParsedResult(batch.batchId(), parsedPages);
        }
    }

    /**
     * Rescues a batch whose {@code sarvam_job_id} is still null past its
     * dispatch lease - either the outbox publish never arrived, or a worker
     * claimed it and then failed to submit (see {@link OcrWorker}). Resetting
     * to PENDING and republishing is the whole recovery: the next delivery to
     * {@code ocr.queue} re-runs {@link OcrWorker} exactly as if this were the
     * first attempt. The republish itself happens inside
     * {@link OcrBatchStateService#claimStalledForRedispatch}, in the same
     * transaction as the claim.
     */
    public void redispatchStalledBatches() {
        List<OcrBatchStateService.ClaimedForRedispatch> stalled = stateService.claimStalledForRedispatch(
                ocrProperties.dispatch().maxSubmitAttempts(), ocrProperties.poller().claimBatchSize(),
                ocrProperties.dispatch().lease());
        for (OcrBatchStateService.ClaimedForRedispatch batch : stalled) {
            log.warn("Redispatching stalled batch {} for document {} (pages {})",
                    batch.batchId(), batch.documentId(), batch.pageRange());
        }
    }
}
