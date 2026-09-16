package com.bloom.eventmanager.ingestion.ocr;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.util.LoadLibs;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.ingestion.config.IngestionProperties;
import com.bloom.eventmanager.ingestion.domain.OcrEngine;

/**
 * Renders each page to an image and runs Tesseract on it.
 *
 * <p>V1 dug embedded images out of the PDF operator list and OCR'd those.
 * Rendering the whole page is simpler, catches text drawn as vector paths
 * too, and is the standard PDFBox + Tess4J approach.
 */
@Component
public class TesseractOcrEngine implements OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrEngine.class);

    private final float dpi;
    private final String language;
    private final Path dataPath;

    public TesseractOcrEngine(IngestionProperties properties) {
        this.dpi = properties.ocrDpi();
        this.language = properties.ocrLanguage();
        this.dataPath = properties.ocrDataPath().isBlank()
                ? LoadLibs.extractTessResources("tessdata").toPath()
                : Path.of(properties.ocrDataPath());
    }

    @Override
    public boolean available() {
        try {
            newTesseract().doOCR(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB));
            return true;
        }
        catch (Throwable unavailable) {
            log.warn("Tesseract not available: {}", unavailable.toString());
            return false;
        }
    }

    @Override
    public String recognize(byte[] pdf) {
        StringBuilder text = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            Tesseract tesseract = newTesseract();
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, dpi, ImageType.RGB);
                String pageText = tesseract.doOCR(image);
                if (pageText != null && !pageText.isBlank()) {
                    text.append(pageText).append('\n');
                    log.info("OCR page {}: {} chars", page + 1, pageText.length());
                }
            }
        }
        catch (IOException | TesseractException ex) {
            log.warn("OCR failed ({}); continuing with what was recognised", ex.getMessage());
        }
        return text.toString();
    }

    private Tesseract newTesseract() {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(dataPath.toString());
        tesseract.setLanguage(language);
        return tesseract;
    }
}
