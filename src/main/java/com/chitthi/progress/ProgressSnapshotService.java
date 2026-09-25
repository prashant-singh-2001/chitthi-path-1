package com.chitthi.progress;

import com.chitthi.document.model.Document;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Builds the current {@link ProgressSnapshot} for a document, fresh from
 * Postgres every time. Deliberately not cached: this is called once per
 * broadcast (at most every stage transition, for however many clients are
 * actually subscribed), so a straight read is simpler than keeping a
 * separately-maintained view in sync.
 */
@Service
public class ProgressSnapshotService {

    private final DocumentRepository documentRepository;
    private final PageRepository pageRepository;

    public ProgressSnapshotService(DocumentRepository documentRepository, PageRepository pageRepository) {
        this.documentRepository = documentRepository;
        this.pageRepository = pageRepository;
    }

    public Optional<ProgressSnapshot> load(UUID documentId) {
        return documentRepository.findById(documentId).map(document -> toSnapshot(document, documentId));
    }

    private ProgressSnapshot toSnapshot(Document document, UUID documentId) {
        var pages = pageRepository.findByDocumentIdOrderByPageNo(documentId).stream()
                .map(page -> new PageProgress(page.getPageNo(), page.getStatus().name()))
                .toList();
        return new ProgressSnapshot(documentId, document.getStatus().name(), pages);
    }
}
