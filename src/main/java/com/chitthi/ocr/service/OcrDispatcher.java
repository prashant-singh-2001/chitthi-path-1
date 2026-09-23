package com.chitthi.ocr.service;

import com.chitthi.messaging.PipelineQueues;
import com.chitthi.ocr.event.OcrBatchCreatedEvent;
import com.chitthi.ocr.message.OcrBatchMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes an {@link OcrBatchCreatedEvent} to {@code ocr.queue} only after
 * the transaction that created the batch row has committed - so a
 * rolled-back upload never queues work for pages that don't exist.
 *
 * <p>This method body is a placeholder for the transactional outbox
 * scheduled for Day 8-9: {@code TransactionalEventListener} gives an
 * after-commit guarantee for the publish, but if the process crashes between
 * the commit and this listener running, or if the broker is unreachable when
 * it does, the batch is stranded at {@code sarvam_job_id IS NULL}.
 * {@link OcrStatusPoller}'s {@code redispatchStalledBatches} sweep is the
 * stopgap for that gap until the {@code outbox} table (already in the V1
 * schema) takes over.
 */
@Component
public class OcrDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OcrDispatcher.class);

    private final RabbitTemplate rabbitTemplate;

    public OcrDispatcher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBatchCreated(OcrBatchCreatedEvent event) {
        log.info("Publishing OCR batch {} for document {} (pages {})",
                event.batchId(), event.documentId(), event.pageRange());
        rabbitTemplate.convertAndSend(PipelineQueues.EXCHANGE, PipelineQueues.OCR_QUEUE,
                new OcrBatchMessage(event.batchId(), event.documentId(), event.language(), event.pageRange()));
    }
}
