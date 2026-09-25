package com.chitthi.tts;

import com.chitthi.audio.WavConcatenator;
import com.chitthi.document.model.Document;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.sarvam.SarvamClient;
import com.chitthi.sarvam.SarvamLanguage;
import com.chitthi.sarvam.SarvamProperties;
import com.chitthi.storage.ObjectStorageService;
import com.chitthi.text.SentenceChunker;
import com.chitthi.tts.message.TtsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Consumes {@code tts.queue}: synthesizes a page's audio into one or two
 * per-language tracks and hands the resulting AUDIO_DONE transition to
 * {@link TtsStateService}. Like {@link com.chitthi.translate.TranslateWorker},
 * every Sarvam call and MinIO write happens outside a transaction.
 *
 * <p>TODO(day8): same duplicate-delivery gap as {@code TranslateWorker} - a
 * redelivered message re-synthesizes audio even though the AUDIO_DONE guard
 * keeps the stored page state correct.
 */
@Component
public class TtsWorker {

    private static final Logger log = LoggerFactory.getLogger(TtsWorker.class);
    private static final String ENGLISH_LANGUAGE = "en-IN";
    private static final String ENGLISH_TRACK = "en";
    private static final String ORIGINAL_TRACK = "orig";

    private final PageRepository pageRepository;
    private final DocumentRepository documentRepository;
    private final SarvamClient sarvamClient;
    private final SarvamProperties sarvamProperties;
    private final ObjectStorageService storageService;
    private final TtsStateService stateService;

    public TtsWorker(PageRepository pageRepository, DocumentRepository documentRepository,
                      SarvamClient sarvamClient, SarvamProperties sarvamProperties,
                      ObjectStorageService storageService, TtsStateService stateService) {
        this.pageRepository = pageRepository;
        this.documentRepository = documentRepository;
        this.sarvamClient = sarvamClient;
        this.sarvamProperties = sarvamProperties;
        this.storageService = storageService;
        this.stateService = stateService;
    }

    @RabbitListener(queues = PipelineQueues.TTS_QUEUE, containerFactory = "pipelineListenerContainerFactory",
            autoStartup = "${chitthi.tts.worker.enabled:true}")
    public void onMessage(TtsMessage message) {
        MDC.put("pageId", message.pageId().toString());
        try {
            handle(message);
        } finally {
            MDC.remove("pageId");
        }
    }

    private void handle(TtsMessage message) {
        Optional<Page> maybePage = pageRepository.findById(message.pageId());
        if (maybePage.isEmpty() || maybePage.get().getStatus() != PageStatus.TRANSLATED) {
            log.info("Page {} is no longer TRANSLATED; skipping TTS", message.pageId());
            return;
        }
        Page page = maybePage.get();
        Document document = documentRepository.findById(page.getDocumentId())
                .orElseThrow(() -> new IllegalStateException("Document not found: " + page.getDocumentId()));

        synthesizeTrack(page, ENGLISH_TRACK, page.getTranslatedText(), ENGLISH_LANGUAGE);

        String sourceLanguage = document.getLanguage();
        if (!SarvamLanguage.isEnglish(sourceLanguage) && SarvamLanguage.supportsTts(sourceLanguage)) {
            synthesizeTrack(page, ORIGINAL_TRACK, page.getOriginalText(), SarvamLanguage.normalize(sourceLanguage));
        }

        boolean applied = stateService.markAudioDone(page.getId(), page.getDocumentId());
        if (!applied) {
            log.info("Page {} changed before its audio completed; discarding", page.getId());
        }
    }

    private void synthesizeTrack(Page page, String track, String text, String languageCode) {
        List<String> chunks = SentenceChunker.chunk(text, sarvamProperties.pipeline().ttsMaxCharsPerRequest());
        List<byte[]> chunkWavs = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            chunkWavs.add(sarvamClient.synthesize(chunk, languageCode));
        }
        byte[] pageWav = WavConcatenator.concat(chunkWavs);
        String key = "documents/%s/audio/%s/%03d.wav".formatted(page.getDocumentId(), track, page.getPageNo());
        storageService.putObject(key, pageWav, "audio/wav");
    }
}
