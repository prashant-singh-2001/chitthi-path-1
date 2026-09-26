package com.chitthi.pipeline.idempotency;

import com.chitthi.document.service.TextHasher;

import java.util.UUID;

/**
 * Builds the deterministic key each {@code stage_task} row is uniquely keyed
 * on: {@code pageId:STAGE:track:chunkIndex:sha256(chunkText)}. Content hash
 * (not chunk position alone) means an edit that changes a chunk's text - Day
 * 10's edit flow - gets a fresh key automatically, since the old key's row
 * simply never matches again; nothing needs to invalidate it explicitly.
 */
public final class IdempotencyKeys {

    private static final String NO_TRACK = "-";

    private IdempotencyKeys() {
    }

    public static String forTranslateChunk(UUID pageId, int chunkIndex, String chunkText) {
        return key(pageId, "TRANSLATE", NO_TRACK, chunkIndex, chunkText);
    }

    public static String forTtsChunk(UUID pageId, String track, int chunkIndex, String chunkText) {
        return key(pageId, "TTS", track, chunkIndex, chunkText);
    }

    private static String key(UUID pageId, String stage, String track, int chunkIndex, String text) {
        return "%s:%s:%s:%d:%s".formatted(pageId, stage, track, chunkIndex, TextHasher.sha256Hex(text));
    }
}
