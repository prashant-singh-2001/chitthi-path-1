package com.chitthi.progress;

import com.chitthi.document.model.DocumentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Reacts to {@link DocumentProgressEvent} after the transaction that raised
 * it commits, loading a fresh snapshot and pushing it to every subscriber of
 * that document. A terminal document status (COMPLETE/PARTIAL) completes the
 * stream, since no further transition will ever come.
 *
 * <p>Skips the DB read entirely when nobody is subscribed - most pipeline
 * transitions happen with no one watching, and a snapshot nobody receives is
 * a wasted query.
 */
@Component
public class ProgressBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(ProgressBroadcaster.class);

    private final SseEmitterRegistry registry;
    private final ProgressSnapshotService snapshotService;

    public ProgressBroadcaster(SseEmitterRegistry registry, ProgressSnapshotService snapshotService) {
        this.registry = registry;
        this.snapshotService = snapshotService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentProgress(DocumentProgressEvent event) {
        if (!registry.hasSubscribers(event.documentId())) {
            return;
        }
        snapshotService.load(event.documentId()).ifPresentOrElse(
                snapshot -> {
                    registry.sendToAll(event.documentId(), SseEmitter.event().name("progress").data(snapshot));
                    if (DocumentStatus.valueOf(snapshot.status()).isTerminal()) {
                        registry.completeAll(event.documentId());
                    }
                },
                () -> log.warn("DocumentProgressEvent for {} but the document no longer exists", event.documentId()));
    }
}
