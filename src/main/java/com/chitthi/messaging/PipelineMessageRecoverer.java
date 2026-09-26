package com.chitthi.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.chitthi.pipeline.idempotency.StageTaskBusyException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * The recovery action Spring AMQP's retry interceptor runs after a single
 * failed delivery ({@code spring.rabbitmq.listener.simple.retry.max-attempts:
 * 1} - our own tiered delay replaces its in-memory backoff), for every queue
 * built on {@code pipelineListenerContainerFactory} (translate, TTS and
 * assemble) as well as the OCR factory.
 *
 * <p>Three outcomes:
 * <ul>
 *   <li><b>Pause</b> ({@link RequestNotPermitted}, {@link CallNotPermittedException}
 *       or {@link StageTaskBusyException} anywhere in the cause chain):
 *       nothing went wrong with the message itself, so it goes back to the
 *       first retry tier without spending an attempt.</li>
 *   <li><b>Attempts remaining</b>: republished to the tier matching the
 *       current attempt, with {@code x-chitthi-attempt} incremented.</li>
 *   <li><b>Exhausted</b>: a page-carrying message marks that page FAILED
 *       (which itself enqueues an assemble check via the outbox); either way
 *       the message is finally rejected to its dead-letter queue.</li>
 * </ul>
 */
@Component
public class PipelineMessageRecoverer implements MessageRecoverer {

    private static final Logger log = LoggerFactory.getLogger(PipelineMessageRecoverer.class);
    private static final String ATTEMPT_HEADER = "x-chitthi-attempt";

    private final ObjectMapper objectMapper;
    private final PipelineFailureStateService failureStateService;
    private final RabbitTemplate rabbitTemplate;
    private final RetryProperties retryProperties;

    public PipelineMessageRecoverer(ObjectMapper objectMapper, PipelineFailureStateService failureStateService,
                                     RabbitTemplate rabbitTemplate, RetryProperties retryProperties) {
        this.objectMapper = objectMapper;
        this.failureStateService = failureStateService;
        this.rabbitTemplate = rabbitTemplate;
        this.retryProperties = retryProperties;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        String originalQueue = message.getMessageProperties().getConsumerQueue();

        if (isPause(cause)) {
            log.info("Pausing message from {} (attempt not spent): {}", originalQueue, cause.toString());
            republish(message, originalQueue, retryProperties.delays().get(0), currentAttempt(message));
            return;
        }

        int attempt = currentAttempt(message);
        if (attempt < retryProperties.delays().size()) {
            log.warn("Message from {} failed (attempt {}/{}); retrying", originalQueue, attempt + 1,
                    retryProperties.delays().size(), cause);
            republish(message, originalQueue, retryProperties.delays().get(attempt), attempt + 1);
            return;
        }

        Optional<UUID> pageId = extractPageId(message);
        if (pageId.isPresent()) {
            failureStateService.markPageFailed(pageId.get());
            log.error("Page {} exhausted retries; marked FAILED", pageId.get(), cause);
        } else {
            log.error("Message exhausted retries with no pageId to act on; rejecting to its dead-letter queue", cause);
        }
        throw new AmqpRejectAndDontRequeueException("Exhausted retries", cause);
    }

    /**
     * Republishing under the message's original routing key ({@code
     * originalQueue}) is what lets the retry tier's dead-lettering (see
     * {@code RabbitMqConfig#retryQueues}) return it to the right queue with
     * no explicit {@code dead-letter-routing-key} needed.
     */
    private void republish(Message message, String originalQueue, Duration delay, int nextAttempt) {
        MessageProperties properties = message.getMessageProperties();
        properties.setHeader(ATTEMPT_HEADER, nextAttempt);
        Message retryMessage = new Message(message.getBody(), properties);
        rabbitTemplate.convertAndSend(PipelineQueues.RETRY_EXCHANGE, originalQueue, retryMessage);
        log.debug("Sent message for {} to the {} retry tier", originalQueue, delay);
    }

    private int currentAttempt(Message message) {
        Object header = message.getMessageProperties().getHeaders().get(ATTEMPT_HEADER);
        return header instanceof Number number ? number.intValue() : 0;
    }

    private boolean isPause(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof RequestNotPermitted || current instanceof CallNotPermittedException
                    || current instanceof StageTaskBusyException) {
                return true;
            }
        }
        return false;
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
