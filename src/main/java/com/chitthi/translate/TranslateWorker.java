package com.chitthi.translate;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.sarvam.SarvamClient;
import com.chitthi.sarvam.SarvamLanguage;
import com.chitthi.sarvam.SarvamProperties;
import com.chitthi.text.SentenceChunker;
import com.chitthi.translate.message.TranslateMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Consumes {@code translate.queue}: translates a page's OCR'd text to
 * English and hands it to {@link TranslateStateService} for the guarded
 * write. Chunking and the Sarvam call happen with no transaction open -
 * Translate is an external, rate-limited call that can take seconds per
 * chunk, and a long-running transaction would starve the connection pool the
 * same way {@code DocumentUploadService} avoids for MinIO PUTs.
 *
 * <p>TODO(day8): a redelivered message re-runs every Translate call even
 * though {@link TranslateStateService}'s status/hash guard keeps the stored
 * result correct - the extra spend isn't blocked until {@code stage_task}
 * idempotency keys land.
 */
@Component
public class TranslateWorker {

    private static final Logger log = LoggerFactory.getLogger(TranslateWorker.class);
    private static final String ENGLISH_TARGET = "en-IN";

    private final PageRepository pageRepository;
    private final DocumentRepository documentRepository;
    private final SarvamClient sarvamClient;
    private final SarvamProperties sarvamProperties;
    private final TranslateStateService stateService;

    public TranslateWorker(PageRepository pageRepository, DocumentRepository documentRepository,
                            SarvamClient sarvamClient, SarvamProperties sarvamProperties,
                            TranslateStateService stateService) {
        this.pageRepository = pageRepository;
        this.documentRepository = documentRepository;
        this.sarvamClient = sarvamClient;
        this.sarvamProperties = sarvamProperties;
        this.stateService = stateService;
    }

    @RabbitListener(queues = PipelineQueues.TRANSLATE_QUEUE, containerFactory = "pipelineListenerContainerFactory",
            autoStartup = "${chitthi.translate.worker.enabled:true}")
    public void onMessage(TranslateMessage message) {
        MDC.put("pageId", message.pageId().toString());
        try {
            handle(message);
        } finally {
            MDC.remove("pageId");
        }
    }

    private void handle(TranslateMessage message) {
        Optional<Page> maybePage = pageRepository.findById(message.pageId());
        if (maybePage.isEmpty() || maybePage.get().getStatus() != PageStatus.OCR_DONE) {
            log.info("Page {} is no longer OCR_DONE; skipping translation", message.pageId());
            return;
        }
        Page page = maybePage.get();
        Document document = documentRepository.findById(page.getDocumentId())
                .orElseThrow(() -> new IllegalStateException("Document not found: " + page.getDocumentId()));

        String originalText = page.getOriginalText();
        String translatedText = SarvamLanguage.isEnglish(document.getLanguage())
                ? originalText
                : translate(originalText, document.getLanguage());

        boolean applied = stateService.markTranslated(page.getId(), translatedText, page.getTextHash());
        if (!applied) {
            log.info("Page {} changed before its translation completed; discarding", page.getId());
        }
    }

    private String translate(String text, String sourceLanguage) {
        String normalizedSource = SarvamLanguage.normalize(sourceLanguage);
        List<String> chunks = SentenceChunker.chunk(text, sarvamProperties.pipeline().translateMaxCharsPerRequest());
        StringBuilder result = new StringBuilder();
        for (String chunk : chunks) {
            String translatedChunk = sarvamClient.translate(chunk, normalizedSource, ENGLISH_TARGET);
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(translatedChunk);
        }
        return result.toString();
    }
}
