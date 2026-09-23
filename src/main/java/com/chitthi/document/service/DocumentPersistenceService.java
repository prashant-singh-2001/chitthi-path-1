package com.chitthi.document.service;

import com.chitthi.document.model.Page;
import com.chitthi.document.repository.PageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Owns the one transactional step of an upload: writing the page rows. Kept
 * as its own bean, separate from {@link DocumentUploadService}, because
 * {@code @Transactional} is applied via a Spring proxy — a method calling
 * another {@code @Transactional} method on {@code this} bypasses that proxy
 * and silently runs without a transaction. Routing the call through a
 * different bean is the straightforward way to avoid that pitfall.
 *
 * <p>This is also where OCR batch creation and the after-commit publish of
 * OCR work will be added once the pipeline queue lands, since both need to
 * happen in the same short transaction as the page insert.
 */
@Service
public class DocumentPersistenceService {

    private final PageRepository pageRepository;

    public DocumentPersistenceService(PageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    @Transactional
    public void persistPages(List<Page> pages) {
        pageRepository.saveAll(pages);
    }
}
