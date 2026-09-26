package com.chitthi.messaging;

import com.chitthi.pipeline.idempotency.StageTaskBusyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineMessageRecovererTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PipelineFailureStateService failureStateService = mock(PipelineFailureStateService.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RetryProperties retryProperties = new RetryProperties(List.of(Duration.ofSeconds(5), Duration.ofSeconds(30)));
    private final PipelineMessageRecoverer recoverer =
            new PipelineMessageRecoverer(objectMapper, failureStateService, rabbitTemplate, retryProperties);

    @Test
    void aRealFailureWithAttemptsLeftIsRepublishedToTheFirstTierWithAttemptOne() {
        Message message = jsonMessage("{\"pageId\": \"" + UUID.randomUUID() + "\"}", "translate.queue");

        recoverer.recover(message, new RuntimeException("boom"));

        verify(rabbitTemplate).send(eq(""), eq(PipelineQueues.retryQueueName("translate.queue", Duration.ofSeconds(5))), any(Message.class));
        verify(failureStateService, never()).markPageFailed(any());
    }

    @Test
    void aRealFailureAtTheLastAttemptMarksThePageFailedAndRejects() {
        UUID pageId = UUID.randomUUID();
        when(failureStateService.markPageFailed(pageId)).thenReturn(Optional.of(UUID.randomUUID()));
        Message message = jsonMessage("{\"pageId\": \"" + pageId + "\"}", "translate.queue");
        message.getMessageProperties().setHeader("x-chitthi-attempt", 2);

        assertThatThrownBy(() -> recoverer.recover(message, new RuntimeException("boom")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(failureStateService).markPageFailed(pageId);
        verify(rabbitTemplate, never()).send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), any(Message.class));
    }

    @Test
    void aMessageWithNoPageIdAtTheLastAttemptRejectsWithNoStateChange() {
        Message message = jsonMessage(
                "{\"batchId\": \"" + UUID.randomUUID() + "\", \"documentId\": \"" + UUID.randomUUID()
                        + "\", \"pageRange\": \"1-10\"}", "ocr.queue");
        message.getMessageProperties().setHeader("x-chitthi-attempt", 2);

        assertThatThrownBy(() -> recoverer.recover(message, new RuntimeException("boom")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        verify(failureStateService, never()).markPageFailed(any());
    }

    @Test
    void requestNotPermittedPausesWithoutSpendingAnAttempt() {
        RateLimiter limiter = RateLimiter.of("test", RateLimiterConfig.custom()
                .limitForPeriod(1).limitRefreshPeriod(Duration.ofMinutes(1)).timeoutDuration(Duration.ZERO).build());
        limiter.acquirePermission(); // exhaust the only permit so the next acquisition is refused
        RequestNotPermitted pauseException = captureRequestNotPermitted(limiter);

        Message message = jsonMessage("{\"pageId\": \"" + UUID.randomUUID() + "\"}", "tts.queue");
        message.getMessageProperties().setHeader("x-chitthi-attempt", 1);

        recoverer.recover(message, pauseException);

        verify(rabbitTemplate).send(eq(""), eq(PipelineQueues.retryQueueName("tts.queue", Duration.ofSeconds(5))), any(Message.class));
        verify(failureStateService, never()).markPageFailed(any());
    }

    @Test
    void callNotPermittedPausesWithoutSpendingAnAttempt() {
        CircuitBreaker breaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowSize(1).minimumNumberOfCalls(1).failureRateThreshold(1f)
                .waitDurationInOpenState(Duration.ofMinutes(5)).build());
        breaker.transitionToOpenState();
        CallNotPermittedException pauseException = CallNotPermittedException.createCallNotPermittedException(breaker);

        Message message = jsonMessage("{\"pageId\": \"" + UUID.randomUUID() + "\"}", "translate.queue");

        recoverer.recover(message, pauseException);

        verify(rabbitTemplate).send(eq(""), eq(PipelineQueues.retryQueueName("translate.queue", Duration.ofSeconds(5))), any(Message.class));
        verify(failureStateService, never()).markPageFailed(any());
    }

    @Test
    void stageTaskBusyPausesWithoutSpendingAnAttempt() {
        Message message = jsonMessage("{\"pageId\": \"" + UUID.randomUUID() + "\"}", "translate.queue");

        recoverer.recover(message, new StageTaskBusyException("some-key"));

        verify(rabbitTemplate).send(eq(""), eq(PipelineQueues.retryQueueName("translate.queue", Duration.ofSeconds(5))), any(Message.class));
        verify(failureStateService, never()).markPageFailed(any());
    }

    private RequestNotPermitted captureRequestNotPermitted(RateLimiter limiter) {
        try {
            RateLimiter.decorateSupplier(limiter, () -> "x").get();
        } catch (RequestNotPermitted e) {
            return e;
        }
        throw new AssertionError("Expected RequestNotPermitted");
    }

    private Message jsonMessage(String json, String consumerQueue) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setConsumerQueue(consumerQueue);
        return new Message(json.getBytes(StandardCharsets.UTF_8), properties);
    }
}
