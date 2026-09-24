package com.chitthi.translate.event;

import java.util.UUID;

/**
 * Raised once {@code translate.service.TranslateStateService} successfully
 * moves a page to TRANSLATED, inside that same transaction. The TTS stage's
 * dispatcher only acts on this after commit, mirroring
 * {@link com.chitthi.ocr.event.PageOcrCompletedEvent}'s after-commit pattern.
 */
public record PageTranslatedEvent(UUID pageId) {
}
