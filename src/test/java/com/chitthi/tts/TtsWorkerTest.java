package com.chitthi.tts;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.pipeline.idempotency.StageTaskService;
import com.chitthi.sarvam.SarvamClient;
import com.chitthi.sarvam.SarvamProperties;
import com.chitthi.storage.ObjectStorageService;
import com.chitthi.tts.message.TtsMessage;
import com.chitthi.usage.UsageMeter;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TtsWorkerTest {

    private final PageRepository pageRepository = mock(PageRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final SarvamClient sarvamClient = mock(SarvamClient.class);
    private final SarvamProperties sarvamProperties = new SarvamProperties(
            "https://api.sarvam.ai", "test-key",
            new SarvamProperties.Http(java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(60)),
            new SarvamProperties.RateLimits(10, 60, 60),
            new SarvamProperties.Pipeline(10, 2000, 2500, 5),
            new SarvamProperties.Translate("sarvam-translate:v1"),
            new SarvamProperties.Tts("bulbul:v3", "shubh", 22050));
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final TtsStateService stateService = mock(TtsStateService.class);
    private final StageTaskService stageTaskService = mock(StageTaskService.class);
    private final UsageMeter usageMeter = mock(UsageMeter.class);
    private final TtsWorker worker = new TtsWorker(
            pageRepository, documentRepository, sarvamClient, sarvamProperties, storageService, stateService,
            stageTaskService, usageMeter);

    {
        // The idempotency guard is exercised in StageTaskServiceTest, and the
        // usage ledger in UsageMeterTest; here they just run the call
        // straight through, so these tests keep asserting on
        // SarvamClient/ObjectStorageService the way they did before either
        // existed.
        when(usageMeter.meter(any(), anyString(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        when(stageTaskService.callOnce(anyString(), any(), anyString(), any()))
                .thenAnswer(invocation -> ((Supplier<String>) invocation.getArgument(3)).get());
        when(storageService.getObject(anyString())).thenReturn(generateWav());
    }

    @Test
    void skipsAPageThatIsNoLongerTranslated() {
        UUID pageId = UUID.randomUUID();
        Page page = new Page(UUID.randomUUID(), 1, "k1");
        page.setStatus(PageStatus.AUDIO_DONE);
        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));

        worker.onMessage(new TtsMessage(pageId));

        verify(sarvamClient, never()).synthesize(anyString(), anyString());
        verify(stateService, never()).markAudioDone(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsAMissingPage() {
        UUID pageId = UUID.randomUUID();
        when(pageRepository.findById(pageId)).thenReturn(Optional.empty());

        worker.onMessage(new TtsMessage(pageId));

        verify(stateService, never()).markAudioDone(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void englishSourceOnlyProducesTheEnglishTrack() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Page page = new Page(documentId, 3, "k1");
        page.setStatus(PageStatus.TRANSLATED);
        page.setTranslatedText("hello world");
        Document document = new Document("owner", "title", "en", null, null);
        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(sarvamClient.synthesize(anyString(), eq("en-IN"))).thenReturn(generateWav());
        when(stateService.markAudioDone(page.getId(), documentId)).thenReturn(true);

        worker.onMessage(new TtsMessage(pageId));

        verify(sarvamClient, times(1)).synthesize(anyString(), eq("en-IN"));
        verify(storageService, times(1)).putObject(
                eq("documents/%s/audio/en/003.wav".formatted(documentId)), org.mockito.ArgumentMatchers.any(), eq("audio/wav"));
        verify(storageService, never()).putObject(
                org.mockito.ArgumentMatchers.contains("/audio/orig/"), org.mockito.ArgumentMatchers.any(), anyString());
        // Metered once for the one real chunk call - a stage_task/TTS-cache
        // hit (see StageTaskServiceTest and IdempotencyKeysTest) never
        // reaches the meter at all.
        verify(usageMeter, times(1)).meter(eq(documentId), eq(com.chitthi.sarvam.SarvamResilience.TTS),
                org.mockito.ArgumentMatchers.anyInt(), eq(com.chitthi.usage.UnitType.CHARACTERS), any());
    }

    @Test
    void ttsSupportedNonEnglishSourceProducesBothTracks() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Page page = new Page(documentId, 5, "k1");
        page.setStatus(PageStatus.TRANSLATED);
        page.setOriginalText("नमस्ते");
        page.setTranslatedText("hello");
        Document document = new Document("owner", "title", "hi", null, null);
        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(sarvamClient.synthesize(anyString(), eq("en-IN"))).thenReturn(generateWav());
        when(sarvamClient.synthesize(anyString(), eq("hi-IN"))).thenReturn(generateWav());
        when(stateService.markAudioDone(page.getId(), documentId)).thenReturn(true);

        worker.onMessage(new TtsMessage(pageId));

        verify(sarvamClient, times(1)).synthesize(anyString(), eq("en-IN"));
        verify(sarvamClient, times(1)).synthesize(anyString(), eq("hi-IN"));
        verify(storageService).putObject(
                eq("documents/%s/audio/en/005.wav".formatted(documentId)), org.mockito.ArgumentMatchers.any(), eq("audio/wav"));
        verify(storageService).putObject(
                eq("documents/%s/audio/orig/005.wav".formatted(documentId)), org.mockito.ArgumentMatchers.any(), eq("audio/wav"));
    }

    @Test
    void aNonEnglishSourceThatBulbulDoesNotSupportOnlyProducesTheEnglishTrack() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.TRANSLATED);
        page.setTranslatedText("hello");
        // Assamese is Translate-supported but not one of Bulbul's 11 TTS languages.
        Document document = new Document("owner", "title", "as", null, null);
        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(sarvamClient.synthesize(anyString(), eq("en-IN"))).thenReturn(generateWav());
        when(stateService.markAudioDone(page.getId(), documentId)).thenReturn(true);

        worker.onMessage(new TtsMessage(pageId));

        verify(sarvamClient, times(1)).synthesize(anyString(), anyString());
        // One chunk write plus the concatenated page-level track write.
        verify(storageService, times(2)).putObject(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    void logsButDoesNotThrowWhenTheStateUpdateIsDiscarded() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.TRANSLATED);
        page.setTranslatedText("hello");
        Document document = new Document("owner", "title", "en", null, null);
        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(sarvamClient.synthesize(anyString(), eq("en-IN"))).thenReturn(generateWav());
        when(stateService.markAudioDone(page.getId(), documentId)).thenReturn(false);

        worker.onMessage(new TtsMessage(pageId));

        assertThat(page.getStatus()).isEqualTo(PageStatus.TRANSLATED);
    }

    private static byte[] generateWav() {
        AudioFormat format = new AudioFormat(22050f, 16, 1, true, false);
        byte[] data = new byte[200];
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(data), format, 100)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
