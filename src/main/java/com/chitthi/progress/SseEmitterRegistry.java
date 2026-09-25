package com.chitthi.progress;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory registry of open SSE connections, keyed by document id. Kept
 * generic - it knows nothing about progress snapshots - so it only handles
 * connection lifecycle: registering, broadcasting, and pruning a connection
 * once it closes, times out, or fails to receive.
 *
 * <p>TODO(scale): this only sees connections accepted by this instance.
 * Running several instances would need a shared fanout (e.g. one RabbitMQ
 * topic per document) so a progress update reaches a client connected to a
 * different instance than the one that processed the page.
 */
@Component
public class SseEmitterRegistry {

    private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> emittersByDocument = new ConcurrentHashMap<>();

    public SseEmitter register(UUID documentId, long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        emittersByDocument.computeIfAbsent(documentId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(documentId, emitter));
        emitter.onTimeout(() -> remove(documentId, emitter));
        emitter.onError(e -> remove(documentId, emitter));
        return emitter;
    }

    public boolean hasSubscribers(UUID documentId) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByDocument.get(documentId);
        return emitters != null && !emitters.isEmpty();
    }

    public void sendToAll(UUID documentId, SseEmitter.SseEventBuilder event) {
        for (SseEmitter emitter : emittersFor(documentId)) {
            try {
                emitter.send(event);
            } catch (IOException e) {
                remove(documentId, emitter);
            }
        }
    }

    /**
     * Removes the document's emitters from the registry immediately, then
     * completes each one - rather than completing first and waiting for
     * {@code onCompletion} to prune them, since that callback only fires once
     * the servlet container's async machinery has attached a live request to
     * the emitter. A caller that has already decided a document is done
     * shouldn't depend on that timing to stop tracking it.
     */
    public void completeAll(UUID documentId) {
        CopyOnWriteArrayList<SseEmitter> emitters = emittersByDocument.remove(documentId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                emitter.complete();
            }
        }
    }

    public void sendHeartbeatToAll() {
        for (Map.Entry<UUID, CopyOnWriteArrayList<SseEmitter>> entry : emittersByDocument.entrySet()) {
            for (SseEmitter emitter : new CopyOnWriteArrayList<>(entry.getValue())) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException e) {
                    remove(entry.getKey(), emitter);
                }
            }
        }
    }

    private List<SseEmitter> emittersFor(UUID documentId) {
        return emittersByDocument.getOrDefault(documentId, new CopyOnWriteArrayList<>());
    }

    private void remove(UUID documentId, SseEmitter emitter) {
        emittersByDocument.computeIfPresent(documentId, (id, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}
