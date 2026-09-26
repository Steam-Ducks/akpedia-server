package com.akpedia.server.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import com.akpedia.server.dto.DocumentFileDescriptor;
import com.akpedia.server.dto.DocumentPreview;
import com.akpedia.server.exception.DocumentPageNotFoundException;
import com.akpedia.server.exception.DocumentRenderException;

/**
 * Shows a document page by page, as images, without ever handing out the PDF itself.
 *
 * <p>This is what the in-app viewer reads instead of {@code /documents/{id}/file}: a browser that
 * only ever receives pictures of the pages has no file to save, no text layer to copy, and no
 * native PDF viewer offering its download and print buttons.
 *
 * <p>Access goes through {@link DocumentFileService#describe}, so a page is refused for exactly
 * the documents the file itself would be -- archived, or still being indexed.
 */
@Service
public class DocumentPreviewService {

    /**
     * Resolution pages are rendered at: twice PDF's 72 units per inch, sharp on a high-density
     * screen while keeping an A4 page around a few hundred kilobytes.
     */
    static final float RENDER_DPI = 144f;

    private final DocumentFileService fileService;

    public DocumentPreviewService(DocumentFileService fileService) {
        this.fileService = fileService;
    }

    /**
     * Describes the document for the viewer, refusing it as the file route would.
     *
     * @param documentId document being opened
     * @return its name and number of pages
     */
    public DocumentPreview describe(Long documentId) {
        DocumentFileDescriptor file = fileService.describe(documentId);
        try (PDDocument pdf = load(file)) {
            return new DocumentPreview(documentId, file.filename(), pdf.getNumberOfPages());
        } catch (IOException e) {
            throw new DocumentRenderException(documentId, e);
        }
    }

    /**
     * Renders one page of the document as a PNG.
     *
     * @param documentId document being viewed
     * @param page       page to render, counted from 1
     * @return the PNG bytes of that page
     * @throws DocumentPageNotFoundException if the document has no such page
     */
    public byte[] renderPage(Long documentId, int page) {
        DocumentFileDescriptor file = fileService.describe(documentId);
        try (PDDocument pdf = load(file)) {
            int pageCount = pdf.getNumberOfPages();
            if (page < 1 || page > pageCount) {
                throw new DocumentPageNotFoundException(documentId, page, pageCount);
            }
            BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(page - 1, RENDER_DPI, ImageType.RGB);
            return toPng(image);
        } catch (IOException e) {
            throw new DocumentRenderException(documentId, e);
        }
    }

    private PDDocument load(DocumentFileDescriptor file) throws IOException {
        return Loader.loadPDF(fileService.contentOf(file));
    }

    private static byte[] toPng(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            // Writing to memory does not fail on I/O; anything here is a bug in the encoder.
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

}
