package com.chitthi.tts.event;

import java.util.UUID;

/**
 * Raised once {@code tts.TtsStateService} successfully moves a page to
 * AUDIO_DONE, inside that same transaction. Carries the document id, not the
 * page id: the assemble stage this event feeds acts at document granularity,
 * checking whether every page of a document has reached a terminal state.
 */
public record PageAudioDoneEvent(UUID documentId) {
}
