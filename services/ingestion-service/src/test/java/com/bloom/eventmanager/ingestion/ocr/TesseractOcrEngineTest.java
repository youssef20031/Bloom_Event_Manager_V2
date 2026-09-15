package com.bloom.eventmanager.ingestion.ocr;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.ingestion.config.IngestionProperties;
import com.bloom.eventmanager.ingestion.pdf.PdfBoxTextExtractorTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TesseractOcrEngineTest {

    private final TesseractOcrEngine engine = new TesseractOcrEngine(
            new IngestionProperties("./ISM_PDF", 1000, 200, 100, 300f, "eng", "", 20));

    @Test
    void recognisesTextRenderedIntoAPageImage() throws IOException {
        // Tess4J bundles Windows natives; Linux needs libtesseract installed.
        // Skip rather than fail where the native library is absent.
        assumeTrue(engine.available(), "Tesseract native library not available");

        String text = engine.recognize(PdfBoxTextExtractorTest.pdfWithText("HELLO WORLD"));

        assertThat(text.toUpperCase()).contains("HELLO");
    }

    @Test
    void returnsEmptyForBytesThatAreNotAPdf() {
        assertThat(engine.recognize("nope".getBytes())).isEmpty();
    }
}
