package com.chitthi.document.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an uploaded PDF into one PNG per page, at a DPI high enough for
 * Sarvam's OCR to work with handwriting without producing huge files.
 */
@Component
public class PdfPageSplitter {

    private static final float RENDER_DPI = 200f;

    public List<PageImage> split(byte[] pdfContent) {
        List<PageImage> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfContent)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pageCount = document.getNumberOfPages();
            for (int i = 0; i < pageCount; i++) {
                var image = renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                pages.add(new PageImage(i + 1, out.toByteArray(), "image/png"));
            }
        } catch (IOException e) {
            throw new PageSplitException("Failed to split PDF into page images", e);
        }
        return pages;
    }

    public int countPages(byte[] pdfContent) {
        try (PDDocument document = Loader.loadPDF(pdfContent)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new PageSplitException("Failed to read PDF page count", e);
        }
    }
}
