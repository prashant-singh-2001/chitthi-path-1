package com.chitthi.document;

import com.chitthi.document.service.PageImage;
import com.chitthi.document.service.PdfPageSplitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PdfPageSplitterTest {

    private final PdfPageSplitter splitter = new PdfPageSplitter();

    @Test
    void split_returnsOnePngPerPageInOrder() throws IOException {
        byte[] pdf = buildBlankPdf(12);

        List<PageImage> pages = splitter.split(pdf);

        assertThat(pages).hasSize(12);
        for (int i = 0; i < pages.size(); i++) {
            assertThat(pages.get(i).pageNo()).isEqualTo(i + 1);
            assertThat(pages.get(i).contentType()).isEqualTo("image/png");
            assertThat(pages.get(i).content()).isNotEmpty();
        }
    }

    @Test
    void countPages_matchesActualPageCount() throws IOException {
        byte[] pdf = buildBlankPdf(3);

        assertThat(splitter.countPages(pdf)).isEqualTo(3);
    }

    private byte[] buildBlankPdf(int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
