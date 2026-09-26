package com.chitthi.messaging.outbox;

import com.chitthi.messaging.PipelineQueues;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

/**
 * The read side of the transactional outbox: sweeps rows {@link OutboxService}
 * wrote and publishes them with publisher confirms, so a row is only marked
 * published once RabbitMQ has actually accepted it - not merely once the
 * client-side call returned.
 *
 * <p>The payload is republished byte-for-byte as the JSON {@link OutboxService}
 * serialized. That works with no further type information because every
 * pipeline queue's listener factory sets {@code TypePrecedence.INFERRED}
 * (see {@code RabbitMqConfig}): the consumer's declared parameter type wins
 * over any {@code __TypeId__} header, so this relay never needs to know or
 * carry the payload's Java type.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long CONFIRM_TIMEOUT_MS = 5000;

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;
    private final int purgeAfterDays;

    public OutboxRelay(OutboxRepository outboxRepository, RabbitTemplate rabbitTemplate,
                        MeterRegistry meterRegistry,
                        @Value("${chitthi.outbox.batch-size:50}") int batchSize,
                        @Value("${chitthi.outbox.purge-after-days:7}") int purgeAfterDays) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = batchSize;
        this.purgeAfterDays = purgeAfterDays;
        // A steadily growing count means the relay is falling behind - a
        // Grafana panel Day 11 adds alongside the Sarvam spend/latency ones.
        Gauge.builder("chitthi.outbox.unpublished", outboxRepository, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox rows not yet relayed to RabbitMQ")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${chitthi.outbox.relay-interval-ms:200}")
    @Transactional
    public void relay() {
        for (OutboxMessage message : outboxRepository.lockUnpublished(batchSize)) {
            publish(message);
        }
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgePublished() {
        long deleted = outboxRepository.deleteByPublishedAtBefore(OffsetDateTime.now().minusDays(purgeAfterDays));
        if (deleted > 0) {
            log.info("Purged {} published outbox rows older than {} days", deleted, purgeAfterDays);
        }
    }

    private void publish(OutboxMessage message) {
        try {
            Message amqpMessage = MessageBuilder.withBody(message.getPayload().getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .build();
            rabbitTemplate.invoke(operations -> {
                operations.send(PipelineQueues.EXCHANGE, message.getTopic(), amqpMessage);
                operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
                return null;
            });
            message.setPublishedAt(OffsetDateTime.now());
        } catch (RuntimeException e) {
            log.warn("Failed to relay outbox message {} (topic {}); will retry next sweep",
                    message.getId(), message.getTopic(), e);
            message.setAttempts(message.getAttempts() + 1);
            message.setLastError(truncate(e.getMessage()));
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }
}
