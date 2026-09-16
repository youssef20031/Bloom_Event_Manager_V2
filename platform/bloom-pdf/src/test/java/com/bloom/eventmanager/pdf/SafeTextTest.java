package com.bloom.eventmanager.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeTextTest {

    private final PDFont helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    @Test
    void passesEncodableTextThroughUnchanged() {
        assertThat(SafeText.forFont(helvetica, "Hello, world 123")).isEqualTo("Hello, world 123");
    }

    @Test
    void replacesCharactersTheFontCannotEncodeWithAPlaceholder() {
        // U+2192 RIGHTWARDS ARROW has no glyph in Helvetica's WinAnsiEncoding and
        // used to make PDPageContentStream.showText throw IllegalArgumentException.
        assertThat(SafeText.forFont(helvetica, "→ Round tables")).isEqualTo("? Round tables");
    }

    @Test
    void degradesArabicToPlaceholdersRatherThanThrowingForAWinAnsiFont() {
        // BriefPdfRenderer intentionally has no Arabic font support (V1 parity: it
        // degrades gracefully rather than crashing, same as PDFKit did).
        assertThat(SafeText.forFont(helvetica, "مرحبا")).isEqualTo("?????");
    }

    @Test
    void normalizesEmbeddedControlCharactersToASpace() {
        // A literal \r or \n reaching showText() is itself an unencodable control
        // character for WinAnsiEncoding fonts and throws just like any other
        // unsupported glyph; Windows-style pretty-printed JSON (\r\n) is one
        // realistic source, but any content field could carry a bare newline.
        assertThat(SafeText.forFont(helvetica, "line one\r\nline two")).isEqualTo("line one  line two");
        assertThat(SafeText.forFont(helvetica, "a\tb")).isEqualTo("a b");
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertThat(SafeText.forFont(helvetica, null)).isEqualTo("");
        assertThat(SafeText.forFont(helvetica, "")).isEqualTo("");
    }

    @Test
    void arabicFontStillEncodesArabicTextUnchanged() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PdfFonts fonts = PdfFonts.forDocument(doc);
            String shaped = ArabicText.shapeForPdf("مرحبا");

            assertThat(SafeText.forFont(fonts.arabic(), shaped)).isEqualTo(shaped);
        }
    }
}
