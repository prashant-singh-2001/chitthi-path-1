package com.chitthi.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write side of the transactional outbox: every pipeline dispatch that
 * used to call {@code rabbitTemplate.convertAndSend} directly from an
 * {@code @TransactionalEventListener(AFTER_COMMIT)} now calls
 * {@link #enqueue} from inside the same transaction that changed the state
 * the message announces. That closes the gap the old dispatchers had - a
 * crash or a broker outage between commit and publish used to strand the
 * transition with nothing to act on it.
 *
 * <p>{@code propagation = MANDATORY} is deliberate: this method must never
 * silently run in its own transaction. A caller with no transaction open is
 * a bug (the row would commit before the caller's own state change does, or
 * not at all if the caller's transaction then rolls back), so it fails loudly
 * instead.
 */
@Service
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * @param routingKey the queue name to publish to (see {@code PipelineQueues})
     * @param payload    a record matching that queue's listener's parameter type
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String routingKey, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxRepository.save(new OutboxMessage(routingKey, json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload for topic " + routingKey, e);
        }
    }
}
