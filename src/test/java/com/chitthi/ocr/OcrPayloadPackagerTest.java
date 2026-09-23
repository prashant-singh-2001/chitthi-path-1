package com.chitthi.ocr;

import com.chitthi.document.model.Page;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.ocr.model.PageRange;
import com.chitthi.ocr.service.OcrPayload;
import com.chitthi.ocr.service.OcrPayloadPackager;
import com.chitthi.ocr.service.OcrPayloadPackagingException;
import com.chitthi.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OcrPayloadPackagerTest {

    private final PageRepository pageRepository = mock(PageRepository.class);
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final OcrProperties properties = new OcrProperties(
            new OcrProperties.Worker(true, 2, 4),
            new OcrProperties.Poller(true, java.time.Duration.ofSeconds(1), 20, java.time.Duration.ofSeconds(30)),
            new OcrProperties.Poll(java.time.Duration.ofSeconds(5), 1.5, java.time.Duration.ofSeconds(60), 0.2, 30),
            new OcrProperties.Dispatch(java.time.Duration.ofSeconds(60), 3),
            33_554_432L,
            new OcrProperties.Result(33_554_432L, List.of("markdown")));

    private final OcrPayloadPackager packager = new OcrPayloadPackager(pageRepository, storageService, properties);

    @Test
    void pack_writesOneZeroPaddedEntryPerPageInOrder() throws IOException {
        UUID documentId = UUID.randomUUID();
        List<Page> pages = List.of(
                new Page(documentId, 1, "documents/x/pages/001.png"),
                new Page(documentId, 2, "documents/x/pages/002.png"));
        when(pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNo(documentId, 1, 2)).thenReturn(pages);
        when(storageService.getObject("documents/x/pages/001.png")).thenReturn("one".getBytes());
        when(storageService.getObject("documents/x/pages/002.png")).thenReturn("two".getBytes());

        OcrPayload payload = packager.pack(documentId, new PageRange(1, 2));

        assertThat(payload.filename()).isEqualTo(documentId + "_1-2.zip");
        List<String> entryNames = entryNamesOf(payload.content());
        assertThat(entryNames).containsExactly("page_001.png", "page_002.png");
    }

    @Test
    void pack_rejectsWhenFewerPagesExistThanTheRangeExpects() {
        UUID documentId = UUID.randomUUID();
        when(pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNo(any(), anyInt(), anyInt()))
                .thenReturn(List.of(new Page(documentId, 1, "documents/x/pages/001.png")));

        assertThatThrownBy(() -> packager.pack(documentId, new PageRange(1, 2)))
                .isInstanceOf(OcrPayloadPackagingException.class);
    }

    @Test
    void pack_rejectsAnOversizedPayload() {
        UUID documentId = UUID.randomUUID();
        OcrProperties tinyLimit = new OcrProperties(
                properties.worker(), properties.poller(), properties.poll(), properties.dispatch(),
                10L, properties.result());
        OcrPayloadPackager tightPackager = new OcrPayloadPackager(pageRepository, storageService, tinyLimit);
        when(pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNo(any(), anyInt(), anyInt()))
                .thenReturn(List.of(new Page(documentId, 1, "documents/x/pages/001.png")));
        when(storageService.getObject(any())).thenReturn("far more than ten bytes of content".getBytes());

        assertThatThrownBy(() -> tightPackager.pack(documentId, new PageRange(1, 1)))
                .isInstanceOf(OcrPayloadPackagingException.class);
    }

    private List<String> entryNamesOf(byte[] zipBytes) throws IOException {
        List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}
