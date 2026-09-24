package com.chitthi.messaging;

import com.chitthi.assemble.message.AssembleMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * The recovery action Spring AMQP's retry interceptor runs once a message's
 * configured retries are exhausted, for every queue built on
 * {@code pipelineListenerContainerFactory} (translate, TTS and assemble) as
 * well as the OCR factory - Boot wires whichever single {@link MessageRecoverer}
 * bean exists into every factory {@code SimpleRabbitListenerContainerFactoryConfigurer}
 * builds.
 *
 * <p>Only translate/TTS messages carry a {@code pageId} field; an OCR message
 * (batchId/documentId/pageRange, no pageId) is left with its prior handling -
 * this recoverer only reads the field, and does nothing extra when it is
 * absent. A page whose message did carry one is marked FAILED and an assemble
 * check is queued, so the document doesn't stay stuck waiting on a page that
 * will never finish. Either way, the message is finally rejected to its
 * dead-letter queue rather than silently acknowledged.
 */
@Component
public class PipelineMessageRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(PipelineMessageRecoverer.class);

    private final ObjectMapper objectMapper;
    private final PipelineFailureStateService failureStateService;
    private final RabbitTemplate rabbitTemplate;

    public PipelineMessageRecoverer(ObjectMapper objectMapper, PipelineFailureStateService failureStateService,
                                     RabbitTemplate rabbitTemplate) {
        this.objectMapper = objectMapper;
        this.failureStateService = failureStateService;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        Optional<UUID> pageId = extractPageId(message);
        if (pageId.isPresent()) {
            Optional<UUID> documentId = failureStateService.markPageFailed(pageId.get());
            documentId.ifPresent(id -> rabbitTemplate.convertAndSend(
                    PipelineQueues.EXCHANGE, PipelineQueues.ASSEMBLE_QUEUE, new AssembleMessage(id)));
            log.error("Page {} exhausted retries; marked FAILED", pageId.get(), cause);
        } else {
            log.error("Message exhausted retries with no pageId to act on; rejecting to its dead-letter queue", cause);
        }
        throw new AmqpRejectAndDontRequeueException("Exhausted retries", cause);
    }

    private Optional<UUID> extractPageId(Message message) {
        try {
            JsonNode node = objectMapper.readTree(message.getBody());
            JsonNode pageIdNode = node.get("pageId");
            return pageIdNode == null || pageIdNode.isNull()
                    ? Optional.empty()
                    : Optional.of(UUID.fromString(pageIdNode.asText()));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
