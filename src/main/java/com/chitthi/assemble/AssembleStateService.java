package com.chitthi.assemble;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.DocumentStatus;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.progress.DocumentProgressEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The one transactional write of the assemble stage: promotes every
 * AUDIO_DONE page to INDEXED and settles the document's final status. Kept
 * as its own bean, separate from {@link DocumentAssembler}, for the same
 * {@code @Transactional} self-invocation reason as the other stages' state
 * services - the assembler itself does the (non-transactional) MinIO reads
 * and writes before calling this.
 */
@Service
public class AssembleStateService {

    private final PageRepository pageRepository;
    private final DocumentRepository documentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AssembleStateService(PageRepository pageRepository, DocumentRepository documentRepository,
                                 ApplicationEventPublisher eventPublisher) {
        this.pageRepository = pageRepository;
        this.documentRepository = documentRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void finalizeDocument(UUID documentId, boolean anyPageFailed) {
        List<Page> pages = pageRepository.findByDocumentIdOrderByPageNo(documentId);
        for (Page page : pages) {
            if (page.getStatus() == PageStatus.AUDIO_DONE) {
                page.setStatus(PageStatus.INDEXED);
            }
        }
        pageRepository.saveAll(pages);

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + documentId));
        document.setStatus(anyPageFailed ? DocumentStatus.PARTIAL : DocumentStatus.COMPLETE);
        documentRepository.save(document);

        eventPublisher.publishEvent(new DocumentProgressEvent(documentId));
    }
}
