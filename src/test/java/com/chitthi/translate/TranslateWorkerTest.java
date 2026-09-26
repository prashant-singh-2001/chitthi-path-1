package com.chitthi.translate;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.Page;
import com.chitthi.document.model.PageStatus;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.sarvam.SarvamClient;
import com.chitthi.sarvam.SarvamProperties;
import com.chitthi.translate.message.TranslateMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslateWorkerTest {

    private final PageRepository pageRepository = mock(PageRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final SarvamClient sarvamClient = mock(SarvamClient.class);
    private final SarvamProperties sarvamProperties = new SarvamProperties(
            "https://api.sarvam.ai", "test-key",
            new SarvamProperties.Http(java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(60)),
            new SarvamProperties.RateLimits(10, 60, 60),
            new SarvamProperties.Pipeline(10, 2000, 2500, 5),
            new SarvamProperties.Translate("sarvam-translate:v1"),
            new SarvamProperties.Tts("bulbul:v3", "shubh", 22050));
    private final TranslateStateService stateService = mock(TranslateStateService.class);
    private final TranslateWorker worker = new TranslateWorker(
            pageRepository, documentRepository, sarvamClient, sarvamProperties, stateService);

    @Test
    void skipsAPageThatIsNoLongerOcrDone() {
        UUID pageId = UUID.randomUUID();
        Page page = new Page(UUID.randomUUID(), 1, "k1");
        page.setStatus(PageStatus.TRANSLATED);
        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));

        worker.onMessage(new TranslateMessage(pageId));

        verify(sarvamClient, never()).translate(anyString(), anyString(), anyString());
        verify(stateService, never()).markTranslated(any(), any(), any(), any());
    }

    @Test
    void skipsAMissingPage() {
        UUID pageId = UUID.randomUUID();
        when(pageRepository.findById(pageId)).thenReturn(Optional.empty());

        worker.onMessage(new TranslateMessage(pageId));

        verify(stateService, never()).markTranslated(any(), any(), any(), any());
    }

    @Test
    void englishSourceIsPassedThroughWithNoTranslateCall() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.OCR_DONE);
        page.setOriginalText("hello world");
        page.setTextHash("hash1");
        Document document = new Document("owner", "title", "en", null, null);
        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(stateService.markTranslated(page.getId(), documentId, "hello world", "hash1")).thenReturn(true);

        worker.onMessage(new TranslateMessage(pageId));

        verify(sarvamClient, never()).translate(anyString(), anyString(), anyString());
        verify(stateService).markTranslated(page.getId(), documentId, "hello world", "hash1");
    }

    @Test
    void nonEnglishSourceIsChunkedAndTranslatedThenJoined() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.OCR_DONE);
        // Two 1201-char sentences: each fits in one 2000-char chunk alone, but
        // together they don't, so the chunker must split them into exactly 2.
        String longText = "a".repeat(1200) + ". " + "b".repeat(1200) + ".";
        page.setOriginalText(longText);
        page.setTextHash("hash2");
        Document document = new Document("owner", "title", "hi", null, null);
        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(sarvamClient.translate(anyString(), eq("hi-IN"), eq("en-IN"))).thenReturn("translated");
        when(stateService.markTranslated(eq(page.getId()), eq(documentId), anyString(), eq("hash2"))).thenReturn(true);

        worker.onMessage(new TranslateMessage(pageId));

        verify(sarvamClient, times(2)).translate(anyString(), eq("hi-IN"), eq("en-IN"));
        verify(stateService).markTranslated(page.getId(), documentId, "translated translated", "hash2");
    }

    @Test
    void logsButDoesNotThrowWhenTheStateUpdateIsDiscarded() {
        UUID pageId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Page page = new Page(documentId, 1, "k1");
        page.setStatus(PageStatus.OCR_DONE);
        page.setOriginalText("short text");
        page.setTextHash("hash3");
        Document document = new Document("owner", "title", "en", null, null);
        when(pageRepository.findById(pageId)).thenReturn(Optional.of(page));
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(stateService.markTranslated(page.getId(), documentId, "short text", "hash3")).thenReturn(false);

        List<Page> before = List.of(page);
        worker.onMessage(new TranslateMessage(pageId));

        assertThat(before.get(0).getStatus()).isEqualTo(PageStatus.OCR_DONE);
    }
}
