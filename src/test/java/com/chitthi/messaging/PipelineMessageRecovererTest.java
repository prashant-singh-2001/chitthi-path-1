package com.chitthi.messaging;

import com.chitthi.assemble.message.AssembleMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineMessageRecovererTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PipelineFailureStateService failureStateService = mock(PipelineFailureStateService.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final PipelineMessageRecoverer recoverer =
            new PipelineMessageRecoverer(objectMapper, failureStateService, rabbitTemplate);

    @Test
    void aMessageWithAPageIdMarksThatPageFailedAndQueuesAnAssembleCheck() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(failureStateService.markPageFailed(pageId)).thenReturn(Optional.of(documentId));
        Message message = jsonMessage("{\"pageId\": \"" + pageId + "\"}");

        assertThatThrownBy(() -> recoverer.recover(message, new RuntimeException("boom")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(rabbitTemplate).convertAndSend(
                eq(PipelineQueues.EXCHANGE), eq(PipelineQueues.ASSEMBLE_QUEUE), eq(new AssembleMessage(documentId)));
    }

    @Test
    void aMessageWithNoPageIdIsRejectedWithNoStateChange() {
        Message message = jsonMessage(
                "{\"batchId\": \"" + UUID.randomUUID() + "\", \"documentId\": \"" + UUID.randomUUID()
                        + "\", \"pageRange\": \"1-10\"}");

        assertThatThrownBy(() -> recoverer.recover(message, new RuntimeException("boom")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(failureStateService, never()).markPageFailed(org.mockito.ArgumentMatchers.any());
        verify(rabbitTemplate, never()).convertAndSend(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object.class));
    }

    @Test
    void aPageThatNoLongerExistsQueuesNoAssembleCheckButStillRejects() {
        UUID pageId = UUID.randomUUID();
        when(failureStateService.markPageFailed(pageId)).thenReturn(Optional.empty());
        Message message = jsonMessage("{\"pageId\": \"" + pageId + "\"}");

        assertThatThrownBy(() -> recoverer.recover(message, new RuntimeException("boom")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(rabbitTemplate, never()).convertAndSend(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object.class));
    }

    private Message jsonMessage(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        return new Message(json.getBytes(StandardCharsets.UTF_8), properties);
    }
}
