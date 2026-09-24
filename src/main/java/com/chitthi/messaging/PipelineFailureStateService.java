package com.chitthi.messaging;

import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.PageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The one transactional write {@link PipelineMessageRecoverer} needs: moving
 * a page whose translate or TTS message exhausted retries to FAILED, so it
 * does not sit at OCR_DONE/TRANSLATED forever with nothing explaining why.
 */
@Service
public class PipelineFailureStateService {

    private final PageRepository pageRepository;

    public PipelineFailureStateService(PageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    /**
     * @return the page's document id, so the caller can trigger an assemble
     *         check - empty if the page no longer exists.
     */
    @Transactional
    public Optional<UUID> markPageFailed(UUID pageId) {
        return pageRepository.findById(pageId).map(page -> {
            if (page.getStatus() != PageStatus.INDEXED) {
                page.setStatus(PageStatus.FAILED);
                pageRepository.save(page);
            }
            return page.getDocumentId();
        });
    }
}
