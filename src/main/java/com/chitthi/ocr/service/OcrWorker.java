package com.chitthi.ocr.service;

import com.chitthi.messaging.PipelineQueues;
import com.chitthi.ocr.OcrProperties;
import com.chitthi.ocr.message.OcrBatchMessage;
import com.chitthi.ocr.model.OcrBatch;
import com.chitthi.ocr.model.PageRange;
import com.chitthi.sarvam.SarvamClient;
import com.chitthi.sarvam.dto.DigitiseJobResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Consumes {@code ocr.queue}: claims a batch, packages its pages into a ZIP,
 * and submits it to Sarvam Document AI Digitise. Everything here is either no
 * transaction (packaging, the HTTP call) or a short one via
 * {@link OcrBatchStateService} - nothing holds a database connection open
 * across the submit call.
 *
 * <p>{@code autoStartup} is a property placeholder rather than a hardcoded
 * {@code true} specifically so a test whose Spring context shares a broker
 * with a real upload flow, but has no Sarvam stub, can disable this listener
 * and avoid consuming batches meant for a different test.
 */
@Component
public class OcrWorker {

    private static final Logger log = LoggerFactory.getLogger(OcrWorker.class);

    private final OcrBatchStateService stateService;
    private final OcrPayloadPackager payloadPackager;
    private final SarvamClient sarvamClient;
    private final OcrProperties ocrProperties;

    public OcrWorker(OcrBatchStateService stateService,
                      OcrPayloadPackager payloadPackager,
                      SarvamClient sarvamClient,
                      OcrProperties ocrProperties) {
        this.stateService = stateService;
        this.payloadPackager = payloadPackager;
        this.sarvamClient = sarvamClient;
        this.ocrProperties = ocrProperties;
    }

    @RabbitListener(queues = PipelineQueues.OCR_QUEUE, autoStartup = "${chitthi.ocr.worker.enabled:true}")
    public void onMessage(OcrBatchMessage message) {
        MDC.put("documentId", message.documentId().toString());
        MDC.put("batchId", message.batchId().toString());
        try {
            handle(message);
        } finally {
            MDC.remove("documentId");
            MDC.remove("batchId");
        }
    }

    private void handle(OcrBatchMessage message) {
        Optional<OcrBatch> claimed = stateService.claimForSubmit(
                message.batchId(), ocrProperties.dispatch().maxSubmitAttempts());
        if (claimed.isEmpty()) {
            // Already claimed by another delivery, already submitted, or its
            // submit-attempt budget is spent. Not an error: acking here is
            // correct either way - a still-recoverable batch is picked up by
            // OcrStatusPoller's redispatch sweep on its own schedule, not by
            // retrying this message.
            log.info("Batch {} could not be claimed for submission; skipping", message.batchId());
            return;
        }

        PageRange range = PageRange.parse(message.pageRange());
        try {
            OcrPayload payload = payloadPackager.pack(message.documentId(), range);
            DigitiseJobResponse response = sarvamClient.submitDigitiseJob(
                    payload.content(), payload.filename(), message.language());
            OffsetDateTime nextPollAt = OffsetDateTime.now().plus(ocrProperties.poll().initialDelay());
            stateService.recordJobId(message.batchId(), response.jobId(), nextPollAt);
            log.info("Submitted OCR batch {} as Sarvam job {}", message.batchId(), response.jobId());
        } catch (RequestNotPermitted | CallNotPermittedException e) {
            // The rate limiter or circuit breaker turned this away before
            // Sarvam was ever called - nothing paid happened, so the claim
            // is released rather than counted as a failed attempt, and the
            // message is rethrown for redelivery rather than treated as a
            // real failure (see PipelineMessageRecoverer's pause handling).
            log.warn("Submit for OCR batch {} was paused by resilience limits; releasing claim", message.batchId(), e);
            stateService.releaseClaim(message.batchId());
            throw e;
        } catch (RuntimeException e) {
            log.error("Failed to submit OCR batch {}", message.batchId(), e);
            stateService.recordSubmitFailure(message.batchId(), e.getMessage());
            // The batch is now RUNNING with no sarvam_job_id, so an AMQP
            // redelivery of this same message cannot re-claim it (claimForSubmit
            // only matches PENDING) - rethrowing mainly gets this failure onto
            // the dead-letter queue as a visible signal. Actual recovery is
            // OcrStatusPoller's redispatch sweep, once next_poll_at (set at
            // batch creation) elapses.
            throw e;
        }
    }
}
