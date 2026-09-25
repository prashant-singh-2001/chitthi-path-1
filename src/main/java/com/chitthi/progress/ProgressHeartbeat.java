package com.chitthi.progress;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps idle SSE connections alive and prunes dead ones. Without this, a
 * document that sits between transitions for a while (waiting on a
 * rate-limited Sarvam call, say) would let a reverse proxy time out and
 * close a perfectly healthy connection.
 */
@Component
public class ProgressHeartbeat {

    private final SseEmitterRegistry registry;

    public ProgressHeartbeat(SseEmitterRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${chitthi.progress.heartbeat-interval-ms:15000}")
    public void sendHeartbeats() {
        registry.sendHeartbeatToAll();
    }
}
