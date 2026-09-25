package com.chitthi.progress;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgressBroadcasterTest {

    private final SseEmitterRegistry registry = mock(SseEmitterRegistry.class);
    private final ProgressSnapshotService snapshotService = mock(ProgressSnapshotService.class);
    private final ProgressBroadcaster broadcaster = new ProgressBroadcaster(registry, snapshotService);

    @Test
    void skipsTheDatabaseReadWhenNobodyIsSubscribed() {
        UUID documentId = UUID.randomUUID();
        when(registry.hasSubscribers(documentId)).thenReturn(false);

        broadcaster.onDocumentProgress(new DocumentProgressEvent(documentId));

        verify(snapshotService, never()).load(any());
    }

    @Test
    void sendsASnapshotToEverySubscriberWhenTheDocumentIsStillInFlight() {
        UUID documentId = UUID.randomUUID();
        when(registry.hasSubscribers(documentId)).thenReturn(true);
        ProgressSnapshot snapshot = new ProgressSnapshot(documentId, "PROCESSING", List.of());
        when(snapshotService.load(documentId)).thenReturn(Optional.of(snapshot));

        broadcaster.onDocumentProgress(new DocumentProgressEvent(documentId));

        verify(registry).sendToAll(eq(documentId), any(SseEmitter.SseEventBuilder.class));
        verify(registry, never()).completeAll(documentId);
    }

    @Test
    void completesTheStreamWhenTheDocumentReachesComplete() {
        UUID documentId = UUID.randomUUID();
        when(registry.hasSubscribers(documentId)).thenReturn(true);
        ProgressSnapshot snapshot = new ProgressSnapshot(documentId, "COMPLETE", List.of());
        when(snapshotService.load(documentId)).thenReturn(Optional.of(snapshot));

        broadcaster.onDocumentProgress(new DocumentProgressEvent(documentId));

        verify(registry).completeAll(documentId);
    }

    @Test
    void completesTheStreamWhenTheDocumentReachesPartial() {
        UUID documentId = UUID.randomUUID();
        when(registry.hasSubscribers(documentId)).thenReturn(true);
        ProgressSnapshot snapshot = new ProgressSnapshot(documentId, "PARTIAL", List.of());
        when(snapshotService.load(documentId)).thenReturn(Optional.of(snapshot));

        broadcaster.onDocumentProgress(new DocumentProgressEvent(documentId));

        verify(registry).completeAll(documentId);
    }

    @Test
    void doesNotThrowWhenTheDocumentNoLongerExists() {
        UUID documentId = UUID.randomUUID();
        when(registry.hasSubscribers(documentId)).thenReturn(true);
        when(snapshotService.load(documentId)).thenReturn(Optional.empty());

        broadcaster.onDocumentProgress(new DocumentProgressEvent(documentId));

        verify(registry, never()).sendToAll(any(), any());
        verify(registry, never()).completeAll(any());
    }
}
