package com.chitthi.document;

import com.chitthi.document.model.Page;
import com.chitthi.document.repository.DocumentRepository;
import com.chitthi.document.service.DocumentPersistenceService;
import com.chitthi.document.service.DocumentUploadService;
import com.chitthi.document.service.PdfPageSplitter;
import com.chitthi.document.service.UploadValidationException;
import com.chitthi.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A page-limit rejection must happen before rendering, not after: PDFBox
 * rendering an oversized PDF at 200 DPI is the expensive part, and
 * {@link PdfPageSplitter#countPages} exists precisely so the limit can be
 * checked first.
 */
class DocumentUploadServiceTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentPersistenceService persistenceService = mock(DocumentPersistenceService.class);
    private final ObjectStorageService storageService = mock(ObjectStorageService.class);
    private final PdfPageSplitter pdfPageSplitter = mock(PdfPageSplitter.class);

    private final DocumentUploadService uploadService = new DocumentUploadService(
            documentRepository, persistenceService, storageService, pdfPageSplitter);

    @Test
    void oversizedPdf_isRejectedWithoutRendering() {
        MockMultipartFile pdf = new MockMultipartFile("files", "letter.pdf", "application/pdf", "fake".getBytes());
        when(pdfPageSplitter.countPages(any())).thenReturn(31);

        assertThatThrownBy(() -> uploadService.upload("user", "title", "hi", null, null, List.of(pdf)))
                .isInstanceOf(UploadValidationException.class)
                .hasMessageContaining("31")
                .hasMessageContaining("30");

        verify(pdfPageSplitter, never()).split(any());
    }

    @Test
    void upload_storesEachImageAndPersistsPagesThroughTheSeparateBean() {
        MockMultipartFile page1 = new MockMultipartFile("files", "a.png", "image/png", "one".getBytes());
        MockMultipartFile page2 = new MockMultipartFile("files", "b.jpg", "image/jpeg", "two".getBytes());
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        uploadService.upload("user", "title", "hi", null, null, List.of(page1, page2));

        verify(storageService, times(2)).putObject(any(), any(), any());

        // persistPages is called on a different bean than DocumentUploadService
        // itself: @Transactional only takes effect through the Spring proxy of
        // that bean, and a same-class call here would silently run with no
        // transaction at all.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Page>> pagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(persistenceService).persistPages(pagesCaptor.capture());
        assertThat(pagesCaptor.getValue()).hasSize(2)
                .extracting(Page::getPageNo)
                .containsExactly(1, 2);
    }
}
