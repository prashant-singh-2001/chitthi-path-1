package com.chitthi.tts;

import com.chitthi.messaging.PipelineQueues;
import com.chitthi.translate.event.PageTranslatedEvent;
import com.chitthi.tts.message.TtsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes a {@link TtsMessage} to {@code tts.queue} only after the
 * transaction that marked a page TRANSLATED has committed - the same
 * after-commit reasoning as {@link com.chitthi.translate.TranslateDispatcher}.
 */
@Component
public class TtsDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TtsDispatcher.class);

    private final RabbitTemplate rabbitTemplate;

    public TtsDispatcher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPageTranslated(PageTranslatedEvent event) {
        log.info("Publishing TTS work for page {}", event.pageId());
        rabbitTemplate.convertAndSend(PipelineQueues.EXCHANGE, PipelineQueues.TTS_QUEUE,
                new TtsMessage(event.pageId()));
    }
}
