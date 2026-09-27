package com.akpedia.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Scanned PDFs often store their pages as JBIG2 images. PDFBox decodes them only when a JBIG2
 * ImageIO reader is on the classpath; without one it skips the image and the viewer shows a
 * blank page.
 */
class Jbig2SupportTest {

    @Test
    @DisplayName("a JBIG2 image reader is available for PDFBox to render scanned pages")
    void jbig2ReaderIsRegistered() {
        assertThat(ImageIO.getImageReadersByFormatName("JBIG2").hasNext()).isTrue();
    }

}