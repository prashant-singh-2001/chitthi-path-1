package com.chitthi.document;

import com.chitthi.audio.AudioProperties;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.document.service.DocumentNotFoundException;
import com.chitthi.document.service.DocumentUploadService;
import com.chitthi.document.web.AudioUrlResponse;
import com.chitthi.document.web.DocumentController;
import com.chitthi.progress.ProgressProperties;
import com.chitthi.progress.ProgressSnapshotService;
import com.chitthi.progress.SseEmitterRegistry;
import com.chitthi.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentControllerAudioTest {

    private final DocumentUploadService uploadService = mock(DocumentUploadService.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final PageRepository pageRepository = mock(PageRepository.class);
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final AudioProperties audioProperties = new AudioProperties(Duration.ofMinutes(15));
    private final SseEmitterRegistry emitterRegistry = mock(SseEmitterRegistry.class);
    private final ProgressSnapshotService snapshotService = mock(ProgressSnapshotService.class);
    private final ProgressProperties progressProperties = new ProgressProperties(Duration.ofMinutes(30), 15000);
    private final com.chitthi.document.service.DocumentRetryService retryService = mock(com.chitthi.document.service.DocumentRetryService.class);
    private final DocumentController controller = new DocumentController(
            uploadService, documentRepository, pageRepository, storageService, audioProperties,
            emitterRegistry, snapshotService, progressProperties, retryService);

    @Test
    void rejectsAnUnknownLanguage() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> controller.audio(id, "fr"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void returns404ForAMissingDocument() {
        UUID id = UUID.randomUUID();
        when(documentRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> controller.audio(id, "en"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void returnsAPresignedUrlWhenTheTrackExists() {
        UUID id = UUID.randomUUID();
        when(documentRepository.existsById(id)).thenReturn(true);
        String key = "documents/%s/audio/en.wav".formatted(id);
        when(storageService.exists(key)).thenReturn(true);
        when(storageService.presignedGetUrl(key, Duration.ofMinutes(15))).thenReturn("https://minio/presigned");

        AudioUrlResponse response = controller.audio(id, "en");

        assertThat(response.url()).isEqualTo("https://minio/presigned");
        assertThat(response.fallback()).isFalse();
    }

    @Test
    void fallsBackToEnglishWhenNoOriginalTrackExists() {
        UUID id = UUID.randomUUID();
        when(documentRepository.existsById(id)).thenReturn(true);
        String origKey = "documents/%s/audio/orig.wav".formatted(id);
        String enKey = "documents/%s/audio/en.wav".formatted(id);
        when(storageService.exists(origKey)).thenReturn(false);
        when(storageService.exists(enKey)).thenReturn(true);
        when(storageService.presignedGetUrl(enKey, Duration.ofMinutes(15))).thenReturn("https://minio/en");

        AudioUrlResponse response = controller.audio(id, "orig");

        assertThat(response.url()).isEqualTo("https://minio/en");
        assertThat(response.fallback()).isTrue();
    }

    @Test
    void returns404WhenNeitherOriginalNorEnglishTrackExists() {
        UUID id = UUID.randomUUID();
        when(documentRepository.existsById(id)).thenReturn(true);
        when(storageService.exists(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        assertThatThrownBy(() -> controller.audio(id, "orig"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void returns404ForEnglishWhenItDoesNotExistWithNoFallback() {
        UUID id = UUID.randomUUID();
        when(documentRepository.existsById(id)).thenReturn(true);
        when(storageService.exists(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        assertThatThrownBy(() -> controller.audio(id, "en"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
