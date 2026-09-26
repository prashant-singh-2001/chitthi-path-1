package com.chitthi.document;

import com.chitthi.audio.AudioProperties;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.service.DocumentRetryService;
import com.chitthi.document.service.DocumentUploadService;
import com.chitthi.document.service.PageEditService;
import com.chitthi.document.web.DocumentController;
import com.chitthi.document.web.PageTextEditRequest;
import com.chitthi.document.web.PageView;
import com.chitthi.progress.ProgressProperties;
import com.chitthi.progress.ProgressSnapshotService;
import com.chitthi.progress.SseEmitterRegistry;
import com.chitthi.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Only the {@code PUT .../text} status-code mapping - {@link PageEditServiceTest}
 * already covers every business rule the service enforces.
 */
class DocumentControllerEditTest {

    private final DocumentUploadService uploadService = mock(DocumentUploadService.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final PageRepository pageRepository = mock(PageRepository.class);
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final AudioProperties audioProperties = new AudioProperties(Duration.ofMinutes(15));
    private final SseEmitterRegistry emitterRegistry = mock(SseEmitterRegistry.class);
    private final ProgressSnapshotService snapshotService = mock(ProgressSnapshotService.class);
    private final ProgressProperties progressProperties = new ProgressProperties(Duration.ofMinutes(30), 15000);
    private final DocumentRetryService retryService = mock(DocumentRetryService.class);
    private final PageEditService pageEditService = mock(PageEditService.class);
    private final DocumentController controller = new DocumentController(
            uploadService, documentRepository, pageRepository, storageService, audioProperties,
            emitterRegistry, snapshotService, progressProperties, retryService, pageEditService);

    @Test
    void editPageText_returns202WithTheUpdatedPageView() {
        UUID documentId = UUID.randomUUID();
        Page page = new Page(documentId, 2, "k2");
        page.setStatus(PageStatus.OCR_DONE);
        page.setOriginalText("corrected");
        when(pageEditService.editText(documentId, 2, "corrected")).thenReturn(page);

        ResponseEntity<PageView> response = controller.editPageText(documentId, 2, new PageTextEditRequest("corrected"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().pageNo()).isEqualTo(2);
        assertThat(response.getBody().originalText()).isEqualTo("corrected");
    }
}
