package com.chitthi.translate;

import com.chitthi.messaging.PipelineQueues;
import com.chitthi.ocr.event.PageOcrCompletedEvent;
import com.chitthi.translate.message.TranslateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes a {@link TranslateMessage} to {@code translate.queue} only after
 * the transaction that marked a page OCR_DONE has committed - see
 * {@link com.chitthi.ocr.service.OcrDispatcher} for why after-commit matters
 * here and the same transactional-outbox gap this stopgaps until Day 8-9.
 */
@Component
public class TranslateDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TranslateDispatcher.class);

    private final RabbitTemplate rabbitTemplate;

    public TranslateDispatcher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPageOcrCompleted(PageOcrCompletedEvent event) {
        log.info("Publishing translate work for page {}", event.pageId());
        rabbitTemplate.convertAndSend(PipelineQueues.EXCHANGE, PipelineQueues.TRANSLATE_QUEUE,
                new TranslateMessage(event.pageId()));
    }
}
