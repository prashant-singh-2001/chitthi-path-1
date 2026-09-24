package com.chitthi.assemble;

import com.chitthi.assemble.message.AssembleMessage;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.tts.event.PageAudioDoneEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes an {@link AssembleMessage} to {@code assemble.queue} only after
 * the transaction that marked a page AUDIO_DONE has committed. Unlike the
 * OCR/translate/TTS dispatchers, several of these fire per document (one per
 * page reaching AUDIO_DONE) - harmless, since {@link DocumentAssembler}
 * checks every page's state itself and a redundant run just rewrites the
 * same track files.
 */
@Component
public class AssembleDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AssembleDispatcher.class);

    private final RabbitTemplate rabbitTemplate;

    public AssembleDispatcher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPageAudioDone(PageAudioDoneEvent event) {
        log.info("Checking whether document {} is ready to assemble", event.documentId());
        rabbitTemplate.convertAndSend(PipelineQueues.EXCHANGE, PipelineQueues.ASSEMBLE_QUEUE,
                new AssembleMessage(event.documentId()));
    }
}
