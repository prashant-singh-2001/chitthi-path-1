package com.chitthi.document.service;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.messaging.PipelineQueues;
import com.chitthi.messaging.outbox.OutboxService;
import com.chitthi.progress.DocumentProgressEvent;
import com.chitthi.translate.message.TranslateMessage;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * FR8: editing a page's text invalidates only that page's translation and
 * audio. Everything downstream of the edit re-runs through the ordinary
 * pipeline - this service's only job is the one state transition
 * ({@code INDEXED} or any other non-PENDING status back to {@code OCR_DONE})
 * and enqueuing the translate work that follows from it, exactly like
 * {@link com.chitthi.ocr.service.OcrResultApplier} does for a page that just
 * finished OCR for the first time.
 *
 * <p>A page whose OCR never recovered any text (FAILED, {@code
 * original_text} null) is not a special case here - typing the text in by
 * hand and calling this method is that page's recovery path, which is
 * exactly what Day 8-9's manual retry endpoint could not do for an
 * OCR-stage failure.
 *
 * <p><b>TODO(known race):</b> a TTS job already in flight for the pre-edit
 * text can still finish and overwrite the page-level track file after the
 * new one lands, if it happens to finish last. It never corrupts the page's
 * own state - its {@code markAudioDone} call fails once the page has moved
 * off TRANSLATED - but the audio file briefly served could be stale until
 * the new job also finishes. Fixing this properly means keying page-level
 * track files by content hash too, which is out of scope here.
 */
@Service
public class PageEditService {

    private static final int MAX_TEXT_LENGTH = 20_000;

    private final DocumentRepository documentRepository;
    private final PageRepository pageRepository;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;

    public PageEditService(DocumentRepository documentRepository, PageRepository pageRepository,
                            OutboxService outboxService, ApplicationEventPublisher eventPublisher) {
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
        this.outboxService = outboxService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Page editText(UUID documentId, int pageNo, String text) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        Page page = pageRepository.findByDocumentIdAndPageNo(documentId, pageNo)
                .orElseThrow(() -> new PageNotFoundException(documentId, pageNo));

        if (page.getStatus() == PageStatus.PENDING) {
            throw new PageNotReadyException(
                    "Page %d has not finished OCR yet; it has no text to edit".formatted(pageNo));
        }
        if (text == null || text.isBlank()) {
            throw new InvalidPageTextException("Page text must not be blank");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidPageTextException("Page text must be %d characters or fewer".formatted(MAX_TEXT_LENGTH));
        }

        String newHash = TextHasher.sha256Hex(text);
        if (page.getStatus() != PageStatus.FAILED && newHash.equals(page.getTextHash())) {
            // Unchanged text: no-op, so saving an edit dialog with nothing
            // actually changed doesn't re-run translation and audio for
            // free. A FAILED page always proceeds even if the hash happens
            // to match, since FAILED means nothing downstream has run yet.
            return page;
        }

        page.setOriginalText(text);
        page.setTextHash(newHash);
        page.setTranslatedText(null);
        page.setEdited(true);
        page.setStatus(PageStatus.OCR_DONE);
        pageRepository.save(page);

        document.setStatus(DocumentStatus.PROCESSING);
        documentRepository.save(document);

        outboxService.enqueue(PipelineQueues.TRANSLATE_QUEUE, new TranslateMessage(page.getId()));
        eventPublisher.publishEvent(new DocumentProgressEvent(documentId));

        return page;
    }
}
