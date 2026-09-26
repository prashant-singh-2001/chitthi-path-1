package com.chitthi.pipeline.idempotency;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyKeysTest {

    @Test
    void translateKeysAreScopedToThePageAndChunkPosition() {
        UUID pageA = UUID.randomUUID();
        UUID pageB = UUID.randomUUID();

        String keyA = IdempotencyKeys.forTranslateChunk(pageA, 0, "same text");
        String keyB = IdempotencyKeys.forTranslateChunk(pageB, 0, "same text");
        String keyADifferentIndex = IdempotencyKeys.forTranslateChunk(pageA, 1, "same text");

        assertThat(keyA).isNotEqualTo(keyB);
        assertThat(keyA).isNotEqualTo(keyADifferentIndex);
    }

    @Test
    void translateKeyChangesWhenTheTextChanges() {
        UUID pageId = UUID.randomUUID();

        String before = IdempotencyKeys.forTranslateChunk(pageId, 0, "original text");
        String after = IdempotencyKeys.forTranslateChunk(pageId, 0, "edited text");

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void ttsKeysAreSharedAcrossPagesForTheSameOwnerVoiceAndText() {
        String key1 = IdempotencyKeys.forTtsAudio("owner-1", "en-IN", "shubh", "bulbul:v3", 22050, "hello world");
        String key2 = IdempotencyKeys.forTtsAudio("owner-1", "en-IN", "shubh", "bulbul:v3", 22050, "hello world");

        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void ttsKeysDifferAcrossOwnersForTheSameText() {
        String owner1Key = IdempotencyKeys.forTtsAudio("owner-1", "en-IN", "shubh", "bulbul:v3", 22050, "hello");
        String owner2Key = IdempotencyKeys.forTtsAudio("owner-2", "en-IN", "shubh", "bulbul:v3", 22050, "hello");

        assertThat(owner1Key).isNotEqualTo(owner2Key);
    }

    @Test
    void ttsKeysDifferWhenTheVoiceSettingsChange() {
        String baseline = IdempotencyKeys.forTtsAudio("owner-1", "en-IN", "shubh", "bulbul:v3", 22050, "hello");
        String differentSpeaker = IdempotencyKeys.forTtsAudio("owner-1", "en-IN", "anushka", "bulbul:v3", 22050, "hello");
        String differentModel = IdempotencyKeys.forTtsAudio("owner-1", "en-IN", "shubh", "bulbul:v2", 22050, "hello");
        String differentSampleRate = IdempotencyKeys.forTtsAudio("owner-1", "en-IN", "shubh", "bulbul:v3", 16000, "hello");
        String differentLanguage = IdempotencyKeys.forTtsAudio("owner-1", "hi-IN", "shubh", "bulbul:v3", 22050, "hello");

        assertThat(baseline).isNotEqualTo(differentSpeaker);
        assertThat(baseline).isNotEqualTo(differentModel);
        assertThat(baseline).isNotEqualTo(differentSampleRate);
        assertThat(baseline).isNotEqualTo(differentLanguage);
    }

    @Test
    void ttsContentHashIsWhatTheAudioCacheKeyIsNamedAfter() {
        String hash = IdempotencyKeys.ttsContentHash("en-IN", "shubh", "bulbul:v3", 22050, "hello");
        String key = IdempotencyKeys.forTtsAudio("owner-1", "en-IN", "shubh", "bulbul:v3", 22050, "hello");

        assertThat(key).isEqualTo("TTS:owner-1:" + hash);
    }
}
