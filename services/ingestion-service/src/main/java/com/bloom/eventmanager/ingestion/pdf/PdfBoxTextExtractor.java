package com.bloom.eventmanager.ingestion.pdf;

import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.ingestion.domain.TextExtractor;

@Component
public class PdfBoxTextExtractor implements TextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxTextExtractor.class);

    @Override
    public String extract(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
        catch (IOException ex) {
            // V1: a parse failure is not fatal — the OCR fallback gets its turn.
            log.warn("PDF text extraction failed ({}); OCR fallback will run", ex.getMessage());
            return "";
        }
    }
}
