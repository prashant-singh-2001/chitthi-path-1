package com.chitthi.ocr.service;

import com.chitthi.document.model.Page;
import com.chitthi.document.repository.PageRepository;
import com.chitthi.ocr.OcrProperties;
import com.chitthi.ocr.model.PageRange;
import com.chitthi.storage.ObjectStorageService;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the ZIP a batch submits to Sarvam Document AI Digitise: one entry
 * per page's stored image, named by its <b>chunk-relative</b> position,
 * zero-padded ({@code page_001.png}, {@code page_002.png}, ...). Zero-padding
 * matters because the result ZIP's per-page metadata is almost certainly
 * ordinal within the submitted archive, and any lexicographic sort on
 * Sarvam's side would otherwise put {@code page_10} before {@code page_2}.
 *
 * <p>Ships as ZIP-of-images rather than a sliced PDF - Sarvam's first-class
 * input - because the upload path (see
 * {@link com.chitthi.document.service.DocumentUploadService}) never retains
 * the original PDF, only the rendered page images, and this is the one
 * packaging that covers both the PDF and the JPG/PNG upload paths uniformly.
 */
@Component
public class OcrPayloadPackager {

    private final PageRepository pageRepository;
    private final ObjectStorageService storageService;
    private final OcrProperties ocrProperties;

    public OcrPayloadPackager(PageRepository pageRepository,
                               ObjectStorageService storageService,
                               OcrProperties ocrProperties) {
        this.pageRepository = pageRepository;
        this.storageService = storageService;
        this.ocrProperties = ocrProperties;
    }

    public OcrPayload pack(UUID documentId, PageRange range) {
        List<Page> pages = pageRepository.findByDocumentIdAndPageNoBetweenOrderByPageNo(
                documentId, range.firstPage(), range.lastPage());
        if (pages.size() != range.size()) {
            throw new OcrPayloadPackagingException(
                    "Expected %d pages for document %s range %s but found %d"
                            .formatted(range.size(), documentId, range.format(), pages.size()));
        }

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            int chunkRelativeIndex = 1;
            for (Page page : pages) {
                byte[] content = storageService.getObject(page.getImageKey());
                ZipEntry entry = new ZipEntry("page_%03d.%s".formatted(chunkRelativeIndex, extensionOf(page.getImageKey())));
                entry.setMethod(ZipEntry.DEFLATED);
                zip.putNextEntry(entry);
                zip.write(content);
                zip.closeEntry();
                chunkRelativeIndex++;
            }
        } catch (IOException e) {
            throw new OcrPayloadPackagingException(
                    "Failed to build OCR payload ZIP for document " + documentId, e);
        }

        byte[] zipBytes = buffer.toByteArray();
        if (zipBytes.length > ocrProperties.maxPayloadBytes()) {
            // Fail loudly and locally rather than discovering Sarvam's
            // (undocumented) request-size ceiling as an opaque 413 from a
            // paid endpoint. If this bites in practice, re-encoding at a
            // lower DPI or as JPEG is a one-line change, isolated here.
            throw new OcrPayloadPackagingException(
                    "OCR payload for document %s pages %s is %d bytes, exceeding the %d byte limit"
                            .formatted(documentId, range.format(), zipBytes.length, ocrProperties.maxPayloadBytes()));
        }

        String filename = "%s_%s.zip".formatted(documentId, range.format());
        return new OcrPayload(zipBytes, filename);
    }

    private String extensionOf(String imageKey) {
        int dot = imageKey.lastIndexOf('.');
        return dot >= 0 ? imageKey.substring(dot + 1) : "png";
    }
}
