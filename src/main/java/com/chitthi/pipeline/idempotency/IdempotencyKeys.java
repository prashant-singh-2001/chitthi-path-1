package com.chitthi.pipeline.idempotency;

import com.chitthi.document.service.TextHasher;

import java.util.UUID;

/**
 * Builds the deterministic key each {@code stage_task} row is uniquely keyed
 * on.
 *
 * <p>Translate keys are page-scoped: {@code pageId:TRANSLATE:-:chunkIndex:
 * sha256(chunkText)}. Content hash (not chunk position alone) means an edit
 * that changes a chunk's text (Day 10's edit flow) gets a fresh key
 * automatically, since the old key's row simply never matches again; nothing
 * needs to invalidate it explicitly. Position is still part of the key so
 * two different chunks of the same page that happen to contain identical
 * text don't collide.
 *
 * <p>TTS keys are owner-scoped, not page-scoped: {@code TTS:ownerId:
 * sha256(voice settings|chunkText)} (see {@link #ttsContentHash}). Two pages
 * - even across two documents - that end up asking for the same text in the
 * same voice reuse one cached result, which is FR13's whole point. Only the
 * owner boundary is kept, so one user's audio is never served to another and
 * a hard-delete stays a per-user operation.
 */
public final class IdempotencyKeys {

    private static final String NO_TRACK = "-";

    private IdempotencyKeys() {
    }

    public static String forTranslateChunk(UUID pageId, int chunkIndex, String chunkText) {
        return "%s:TRANSLATE:%s:%d:%s".formatted(pageId, NO_TRACK, chunkIndex, TextHasher.sha256Hex(chunkText));
    }

    public static String forTtsAudio(String ownerId, String languageCode, String speaker, String model,
                                      int sampleRate, String chunkText) {
        return "TTS:%s:%s".formatted(ownerId, ttsContentHash(languageCode, speaker, model, sampleRate, chunkText));
    }

    /**
     * The content hash alone, reused by the caller to name the cached
     * audio's object storage key (see {@code TtsWorker}) - so the
     * idempotency key and the cache location are always derived from the
     * exact same inputs, with no risk of drifting apart.
     */
    public static String ttsContentHash(String languageCode, String speaker, String model, int sampleRate,
                                         String chunkText) {
        return TextHasher.sha256Hex("%s|%s|%s|%d|%s".formatted(languageCode, speaker, model, sampleRate, chunkText));
    }
}
