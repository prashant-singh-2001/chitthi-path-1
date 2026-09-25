package com.chitthi.progress;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SseEmitterRegistryTest {

    private final SseEmitterRegistry registry = new SseEmitterRegistry();

    @Test
    void hasNoSubscribersBeforeAnyoneRegisters() {
        UUID documentId = UUID.randomUUID();

        assertThat(registry.hasSubscribers(documentId)).isFalse();
    }

    @Test
    void hasSubscribersOnceRegistered() {
        UUID documentId = UUID.randomUUID();

        registry.register(documentId, 30_000L);

        assertThat(registry.hasSubscribers(documentId)).isTrue();
    }

    // Removal via the emitter's own onCompletion/onTimeout/onError callbacks
    // (registered in register()) only fires once Spring's DispatcherServlet
    // has attached a live async request to the emitter - calling complete()
    // directly on an emitter with no request behind it is a documented no-op
    // beyond an internal flag, so that path isn't exercisable in a plain unit
    // test. It's covered by the milestone SSE integration test instead, where
    // a real request drives it end to end. completeAll() below is the path
    // this registry actually uses to end a stream, and it removes eagerly.

    @Test
    void sendToAllDeliversToEveryRegisteredEmitterForThatDocument() throws Exception {
        UUID documentId = UUID.randomUUID();
        registry.register(documentId, 30_000L);
        registry.register(documentId, 30_000L);
        UUID otherDocumentId = UUID.randomUUID();
        SseEmitter otherEmitter = registry.register(otherDocumentId, 30_000L);
        java.util.concurrent.atomic.AtomicInteger otherReceived = new java.util.concurrent.atomic.AtomicInteger();
        otherEmitter.onCompletion(otherReceived::incrementAndGet);

        registry.sendToAll(documentId, SseEmitter.event().name("progress").data("snapshot"));

        // Only the other document's subscriber remains untouched.
        assertThat(registry.hasSubscribers(documentId)).isTrue();
        assertThat(otherReceived.get()).isZero();
    }

    @Test
    void completeAllCompletesEveryEmitterForThatDocumentOnly() {
        UUID documentId = UUID.randomUUID();
        registry.register(documentId, 30_000L);
        UUID otherDocumentId = UUID.randomUUID();
        registry.register(otherDocumentId, 30_000L);

        registry.completeAll(documentId);

        assertThat(registry.hasSubscribers(documentId)).isFalse();
        assertThat(registry.hasSubscribers(otherDocumentId)).isTrue();
    }

    @Test
    void heartbeatLeavesHealthyEmittersRegistered() {
        UUID documentId = UUID.randomUUID();
        registry.register(documentId, 30_000L);

        registry.sendHeartbeatToAll();

        assertThat(registry.hasSubscribers(documentId)).isTrue();
    }
}
