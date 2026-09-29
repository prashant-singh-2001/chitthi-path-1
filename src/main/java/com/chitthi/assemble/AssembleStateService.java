package com.chitthi.assemble;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.progress.DocumentProgressEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The one transactional write of the assemble stage: promotes every
 * AUDIO_DONE page to INDEXED and settles the document's final status. Kept
 * as its own bean, separate from {@link DocumentAssembler}, for the same
 * {@code @Transactional} self-invocation reason as the other stages' state
 * services - the assembler itself does the (non-transactional) MinIO reads
 * and writes before calling this.
 *
 * <p>Day 12: this is also where {@code chitthi.document.duration} is
 * recorded - the only place a document reaches COMPLETE, and the normal
 * path to PARTIAL (some page failed or was capped, but the pipeline still
 * ran to the end). A document that never got this far - {@link
 * com.chitthi.ocr.service.OcrResultApplier}'s early-PARTIAL path, where OCR
 * itself failed outright - never ran the pipeline end to end, so it's
 * deliberately excluded from this timer to avoid skewing it, even though it
 * still gets a {@code completed_at} stamp from {@link Document#setStatus}.
 */
@Service
public class AssembleStateService {

    private final PageRepository pageRepository;
    private final DocumentRepository documentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    public AssembleStateService(PageRepository pageRepository, DocumentRepository documentRepository,
                                 ApplicationEventPublisher eventPublisher, MeterRegistry meterRegistry) {
        this.pageRepository = pageRepository;
        this.documentRepository = documentRepository;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void finalizeDocument(UUID documentId, boolean anyPageIncomplete) {
        List<Page> pages = pageRepository.findByDocumentIdOrderByPageNo(documentId);
        for (Page page : pages) {
            if (page.getStatus() == PageStatus.AUDIO_DONE) {
                page.setStatus(PageStatus.INDEXED);
            }
        }
        pageRepository.saveAll(pages);

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + documentId));
        DocumentStatus finalStatus = anyPageIncomplete ? DocumentStatus.PARTIAL : DocumentStatus.COMPLETE;
        document.setStatus(finalStatus);
        documentRepository.save(document);

        // createdAt is only ever null for a Document built by hand in a unit
        // test that never went through Hibernate's @CreationTimestamp - a
        // real document is always persisted (and so timestamped) long before
        // it can reach this method.
        if (document.getCreatedAt() != null) {
            meterRegistry.timer("chitthi.document.duration", "status", finalStatus.name())
                    .record(Duration.between(document.getCreatedAt(), document.getCompletedAt()));
        }

        eventPublisher.publishEvent(new DocumentProgressEvent(documentId));
    }
}
