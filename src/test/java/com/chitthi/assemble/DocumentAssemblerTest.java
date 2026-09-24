package com.chitthi.assemble;

import com.chitthi.assemble.message.AssembleMessage;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAssemblerTest {

    private final PageRepository pageRepository = mock(PageRepository.class);
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final AssembleStateService stateService = mock(AssembleStateService.class);
    private final DocumentAssembler assembler = new DocumentAssembler(pageRepository, storageService, stateService);

    @Test
    void deferWhileAnyPageIsStillInFlight() {
        UUID documentId = UUID.randomUUID();
        Page pending = new Page(documentId, 1, "k1");
        pending.setStatus(PageStatus.TRANSLATED);
        when(pageRepository.findByDocumentIdOrderByPageNo(documentId)).thenReturn(List.of(pending));

        assembler.onMessage(new AssembleMessage(documentId));

        verify(storageService, never()).getObject(anyString());
        verify(stateService, never()).finalizeDocument(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void doesNothingForADocumentWithNoPages() {
        UUID documentId = UUID.randomUUID();
        when(pageRepository.findByDocumentIdOrderByPageNo(documentId)).thenReturn(List.of());

        assembler.onMessage(new AssembleMessage(documentId));

        verify(stateService, never()).finalizeDocument(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void skipsFailedPagesAndAssemblesTracksFromTheRest() {
        UUID documentId = UUID.randomUUID();
        Page page1 = new Page(documentId, 1, "k1");
        page1.setStatus(PageStatus.AUDIO_DONE);
        Page page2 = new Page(documentId, 2, "k2");
        page2.setStatus(PageStatus.FAILED);
        when(pageRepository.findByDocumentIdOrderByPageNo(documentId)).thenReturn(List.of(page1, page2));

        String enKey1 = "documents/%s/audio/en/001.wav".formatted(documentId);
        String origKey1 = "documents/%s/audio/orig/001.wav".formatted(documentId);
        String enKey2 = "documents/%s/audio/en/002.wav".formatted(documentId);
        String origKey2 = "documents/%s/audio/orig/002.wav".formatted(documentId);
        when(storageService.exists(enKey1)).thenReturn(true);
        when(storageService.exists(origKey1)).thenReturn(true);
        when(storageService.exists(enKey2)).thenReturn(true);
        when(storageService.exists(origKey2)).thenReturn(true);
        when(storageService.getObject(enKey1)).thenReturn(generateWav());
        when(storageService.getObject(origKey1)).thenReturn(generateWav());

        assembler.onMessage(new AssembleMessage(documentId));

        // Page 2 is FAILED - its track files must never be read, even though they "exist".
        verify(storageService, never()).getObject(enKey2);
        verify(storageService, never()).getObject(origKey2);
        verify(storageService).putObject(eq("documents/%s/audio/en.wav".formatted(documentId)), any(), eq("audio/wav"));
        verify(storageService).putObject(eq("documents/%s/audio/orig.wav".formatted(documentId)), any(), eq("audio/wav"));
        verify(stateService).finalizeDocument(documentId, true);
    }

    @Test
    void writesNoOrigTrackWhenNoPageHasOne() {
        UUID documentId = UUID.randomUUID();
        Page page1 = new Page(documentId, 1, "k1");
        page1.setStatus(PageStatus.AUDIO_DONE);
        when(pageRepository.findByDocumentIdOrderByPageNo(documentId)).thenReturn(List.of(page1));
        String enKey1 = "documents/%s/audio/en/001.wav".formatted(documentId);
        String origKey1 = "documents/%s/audio/orig/001.wav".formatted(documentId);
        when(storageService.exists(enKey1)).thenReturn(true);
        when(storageService.exists(origKey1)).thenReturn(false);
        when(storageService.getObject(enKey1)).thenReturn(generateWav());

        assembler.onMessage(new AssembleMessage(documentId));

        verify(storageService).putObject(eq("documents/%s/audio/en.wav".formatted(documentId)), any(), eq("audio/wav"));
        verify(storageService, never()).putObject(
                eq("documents/%s/audio/orig.wav".formatted(documentId)), any(), anyString());
        verify(stateService).finalizeDocument(documentId, false);
    }

    private static byte[] generateWav() {
        AudioFormat format = new AudioFormat(22050f, 16, 1, true, false);
        byte[] data = new byte[200];
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(data), format, 100)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
