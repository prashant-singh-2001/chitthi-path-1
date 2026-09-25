package com.chitthi.document;

import com.chitthi.audio.AudioProperties;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.service.DocumentNotFoundException;
import com.chitthi.document.service.DocumentUploadService;
import com.chitthi.document.web.DocumentController;
import com.chitthi.progress.ProgressProperties;
import com.chitthi.progress.ProgressSnapshot;
import com.chitthi.progress.ProgressSnapshotService;
import com.chitthi.progress.SseEmitterRegistry;
import com.chitthi.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentControllerEventsTest {

    private final DocumentUploadService uploadService = mock(DocumentUploadService.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final PageRepository pageRepository = mock(PageRepository.class);
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final AudioProperties audioProperties = new AudioProperties(Duration.ofMinutes(15));
    private final SseEmitterRegistry emitterRegistry = new SseEmitterRegistry();
    private final ProgressSnapshotService snapshotService = mock(ProgressSnapshotService.class);
    private final ProgressProperties progressProperties = new ProgressProperties(Duration.ofMinutes(30), 15000);
    private final DocumentController controller = new DocumentController(
            uploadService, documentRepository, pageRepository, storageService, audioProperties,
            emitterRegistry, snapshotService, progressProperties);

    @Test
    void throws404WhenTheDocumentDoesNotExist() {
        UUID id = UUID.randomUUID();
        when(snapshotService.load(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.events(id)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void registersAndSendsTheInitialSnapshotForAnInFlightDocument() {
        UUID id = UUID.randomUUID();
        ProgressSnapshot snapshot = new ProgressSnapshot(id, "PROCESSING", List.of());
        when(snapshotService.load(id)).thenReturn(Optional.of(snapshot));

        SseEmitter emitter = controller.events(id);

        assertThat(emitter).isNotNull();
        assertThat(emitterRegistry.hasSubscribers(id)).isTrue();
    }

    @Test
    void completesImmediatelyForAnAlreadyTerminalDocument() {
        UUID id = UUID.randomUUID();
        ProgressSnapshot snapshot = new ProgressSnapshot(id, "COMPLETE", List.of());
        when(snapshotService.load(id)).thenReturn(Optional.of(snapshot));

        controller.events(id);

        assertThat(emitterRegistry.hasSubscribers(id)).isFalse();
    }
}
