package com.bloom.eventmanager.proposal.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.pdf.ArabicText;
import com.bloom.eventmanager.pdf.PdfFonts;

import static org.assertj.core.api.Assertions.assertThat;

class SlideCanvasTest {

    @Test
    void wrappingLongArabicTextKeepsWordsInLogicalOrderAcrossLines() throws IOException {
        // Regression guard for the shape-then-wrap defect: shaping and
        // bidi-reordering the WHOLE string before wrapping puts the
        // logically-last words on the first line, so the paragraph reads
        // bottom-to-top. A box just wide enough for the first two words
        // forces a break after them (same width-forcing technique as
        // MixedTextWriterTest.wrappingAnArabicRunKeepsWordsInLogicalOrderAcrossLines),
        // and PDFBox's PDFTextStripper un-shapes and un-reorders extracted
        // Arabic back to logical form, so the full round trip must reproduce
        // the original phrase in its original order.
        String word1 = "كتاب";
        String word2 = "جميل";
        String word3 = "اليوم";
        String phrase = word1 + " " + word2 + " " + word3;

        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PdfFonts fonts = PdfFonts.forDocument(doc);
            float size = 14f;

            float firstTwoWordsWidth = PdfFonts.width(fonts.arabic(), ArabicText.shapeForPdf(word1 + " " + word2), size);
            float allThreeWordsWidth = PdfFonts.width(fonts.arabic(), ArabicText.shapeForPdf(phrase), size);
            float maxWidth = (firstTwoWordsWidth + allThreeWordsWidth) / 2f; // fits word1+word2, not word3 too

            try (SlideCanvas c = new SlideCanvas(doc, fonts)) {
                c.text(phrase, 40, 40, maxWidth, size, SlideCanvas.Weight.REGULAR, Color.BLACK, SlideCanvas.Align.RIGHT);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        try (PDDocument reloaded = Loader.loadPDF(pdf)) {
            String extracted = new PDFTextStripper().getText(reloaded).replaceAll("\\s+", " ").trim();
            assertThat(extracted).isEqualTo(phrase);
        }
    }
}
