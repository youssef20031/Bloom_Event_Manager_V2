package com.bloom.eventmanager.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MixedTextWriterTest {

    @Test
    void drawsMixedArabicAndEnglishAndWrapsLongLines() throws IOException {
        byte[] pdf;
        float yAfter;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PdfFonts fonts = PdfFonts.forDocument(doc);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                yAfter = MixedTextWriter.writeLine(content,
                        ScriptRuns.split("Welcome to **Bloom** فعاليات بناء الفريق and a very long English tail that must wrap onto a second line for sure"),
                        50, 700, 200, 12, 11, fonts);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        assertThat(yAfter).isLessThan(700 - 12); // at least two lines consumed
        try (PDDocument reloaded = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(reloaded);
            assertThat(text).contains("Welcome").contains("Bloom").contains("wrap");
        }
    }

    @Test
    void fontsAreAvailablePerDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PdfFonts fonts = PdfFonts.forDocument(doc);

            assertThat(fonts.arabic().getName()).contains("Amiri");
            assertThat(fonts.arabicBold().getName()).contains("Amiri");
            assertThat(PdfFonts.width(fonts.regular(), "Hello", 12)).isGreaterThan(0);
        }
    }
}
