package com.chitthi.document.service;

import com.chitthi.document.model.Document;
import com.chitthi.document.model.Page;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.storage.ObjectStorageService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates FR1/FR2: validates an upload, splits it into page images,
 * stores each page image in object storage, and hands the page rows to
 * {@link DocumentPersistenceService} to persist. Queueing OCR work for those
 * pages happens inside that same short transaction once the pipeline queue
 * lands.
 *
 * <p>This method is deliberately <b>not</b> transactional: PDF rendering and
 * up to {@link #MAX_PAGES} MinIO PUTs can take tens of seconds for a real
 * scanned document, and holding a Postgres transaction (and a pool
 * connection) open for that long starves the pool under concurrent uploads.
 * Only the page rows themselves are written inside a transaction, in
 * {@link DocumentPersistenceService}, kept as short as a batch insert.
 *
 * <p>The document row is saved before its pages, in its own implicit
 * transaction (Spring Data's {@code save} runs one when none is active), so
 * {@code document.getId()} is available to build each page's object key. A
 * failure between that save and the page persist step leaves a document with
 * no pages and orphaned MinIO objects under {@code documents/{id}/} — an
 * acceptable gap for a solo project: the objects are cleanly identifiable by
 * that prefix for a future delete-orphans job, and the document simply stays
 * empty rather than corrupting other documents' data.
 */
@Service
public class DocumentUploadService {

    static final long MAX_FILE_SIZE_BYTES = 20L * 1024 * 1024;
    static final int MAX_PAGES = 30;

    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final DocumentRepository documentRepository;
    private final DocumentPersistenceService persistenceService;
    private final ObjectStorageService storageService;
    private final PdfPageSplitter pdfPageSplitter;

    public DocumentUploadService(DocumentRepository documentRepository,
                                  DocumentPersistenceService persistenceService,
                                  ObjectStorageService storageService,
                                  PdfPageSplitter pdfPageSplitter) {
        this.documentRepository = documentRepository;
        this.persistenceService = persistenceService;
        this.storageService = storageService;
        this.pdfPageSplitter = pdfPageSplitter;
    }

    public Document upload(String ownerId, String title, String language,
                            List<String> tags, Integer year, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new UploadValidationException("At least one file is required");
        }
        for (MultipartFile file : files) {
            if (file.getSize() > MAX_FILE_SIZE_BYTES) {
                throw new UploadValidationException(
                        "File '%s' exceeds the 20 MB limit".formatted(file.getOriginalFilename()));
            }
        }

        List<PageImage> pageImages = toPageImages(files);
        if (pageImages.size() > MAX_PAGES) {
            throw new UploadValidationException(
                    "Document has %d pages, exceeding the %d page limit".formatted(pageImages.size(), MAX_PAGES));
        }

        Document document = documentRepository.save(new Document(ownerId, title, language, tags, year));

        List<Page> pages = new ArrayList<>(pageImages.size());
        for (PageImage pageImage : pageImages) {
            String extension = pageImage.contentType().equals("image/png") ? "png" : "jpg";
            String imageKey = "documents/%s/pages/%03d.%s".formatted(document.getId(), pageImage.pageNo(), extension);
            storageService.putObject(imageKey, pageImage.content(), pageImage.contentType());
            pages.add(new Page(document.getId(), pageImage.pageNo(), imageKey));
        }

        persistenceService.persistPages(document, pages);
        return document;
    }

    private List<PageImage> toPageImages(List<MultipartFile> files) {
        if (files.size() == 1 && PDF_CONTENT_TYPE.equals(files.get(0).getContentType())) {
            byte[] pdfBytes = readBytes(files.get(0));
            // Count before rendering: a page-limit rejection must not first pay
            // for rendering every page of an oversized PDF at 200 DPI.
            int pageCount = pdfPageSplitter.countPages(pdfBytes);
            if (pageCount > MAX_PAGES) {
                throw new UploadValidationException(
                        "Document has %d pages, exceeding the %d page limit".formatted(pageCount, MAX_PAGES));
            }
            return pdfPageSplitter.split(pdfBytes);
        }

        List<PageImage> pageImages = new ArrayList<>();
        int pageNo = 1;
        for (MultipartFile file : files) {
            String contentType = file.getContentType();
            if (!IMAGE_CONTENT_TYPES.contains(contentType)) {
                throw new UploadValidationException(
                        "Unsupported file type '%s' for '%s'; expected JPG, PNG or a single PDF"
                                .formatted(contentType, file.getOriginalFilename()));
            }
            pageImages.add(new PageImage(pageNo++, readBytes(file), contentType));
        }
        return pageImages;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UploadValidationException("Failed to read uploaded file: " + file.getOriginalFilename());
        }
    }
}
