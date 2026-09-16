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

    @Test
    void arabicRunIsShapedAsAWholeNotWordByWord() throws IOException {
        // Regression guard: shaping word-by-word joins letters correctly within each
        // word but leaves the words themselves in logical order, so the phrase reads
        // backwards even though each word looks right in isolation. PDFBox's text
        // extraction un-shapes and un-reorders whatever was drawn as one coherent run
        // back to logical reading order, so a run shaped as a single unit round-trips
        // back to the exact original phrase; shaped word-by-word, it does not (verified
        // directly: extracting a deliberately per-word-shaped rendering of this same
        // phrase yields "الفريقبناء فعاليات " — the words themselves reversed).
        String phrase = "فعاليات بناء الفريق";
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PdfFonts fonts = PdfFonts.forDocument(doc);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                MixedTextWriter.writeLine(content, ScriptRuns.split(phrase), 50, 700, 500, 12, 11, fonts);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        try (PDDocument reloaded = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(reloaded);
            assertThat(text).contains(phrase);
        }
    }

    @Test
    void wrappingAnArabicRunKeepsWordsInLogicalOrderAcrossLines() throws IOException {
        // Regression guard against the opposite mistake: reordering the whole run
        // first and then wrapping the reordered string would scatter the logically
        // first words onto later lines instead of the first line. Force exactly the
        // first two words onto line one and the third onto line two, and confirm
        // the first two words still extract back out together, in original order.
        String word1 = "فعاليات";
        String word2 = "بناء";
        String word3 = "الفريق";
        String phrase = word1 + " " + word2 + " " + word3;

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PdfFonts fonts = PdfFonts.forDocument(doc);
            float arabicSize = 11f;

            float firstTwoWordsWidth = PdfFonts.width(fonts.arabic(),
                    ArabicText.shapeForPdf(word1 + " " + word2 + " "), arabicSize);
            float allThreeWordsWidth = PdfFonts.width(fonts.arabic(), ArabicText.shapeForPdf(phrase), arabicSize);
            float maxWidth = (firstTwoWordsWidth + allThreeWordsWidth) / 2f; // fits word1+word2, not word3 too

            byte[] pdf;
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                MixedTextWriter.writeLine(content, ScriptRuns.split(phrase), 50, 700, maxWidth, 12, arabicSize,
                        fonts);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();

            try (PDDocument reloaded = Loader.loadPDF(pdf)) {
                String text = new PDFTextStripper().getText(reloaded);
                assertThat(text).contains(word1 + " " + word2);
            }
        }
    }
}
