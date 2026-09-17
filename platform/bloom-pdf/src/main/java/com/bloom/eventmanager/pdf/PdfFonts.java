package com.bloom.eventmanager.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Fonts are bound to a document in PDFBox, so one instance per PDDocument. */
public final class PdfFonts {

    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final PDFont italic = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
    private final PDFont mono = new PDType1Font(Standard14Fonts.FontName.COURIER);
    private final PDFont monoBold = new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);
    private final PDFont arabic;
    private final PDFont arabicBold;

    private PdfFonts(PDDocument document) {
        this.arabic = load(document, "/fonts/Amiri-Regular.ttf");
        this.arabicBold = load(document, "/fonts/Amiri-Bold.ttf");
    }

    public static PdfFonts forDocument(PDDocument document) {
        return new PdfFonts(document);
    }

    private static PDFont load(PDDocument document, String resource) {
        try (InputStream in = PdfFonts.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled font " + resource);
            }
            return PDType0Font.load(document, in);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public PDFont regular() { return regular; }
    public PDFont bold() { return bold; }
    public PDFont italic() { return italic; }
    public PDFont mono() { return mono; }
    public PDFont monoBold() { return monoBold; }
    public PDFont arabic() { return arabic; }
    public PDFont arabicBold() { return arabicBold; }

    public PDFont forRun(ScriptRuns.Run run) {
        if (run.arabic()) {
            return run.bold() ? arabicBold : arabic;
        }
        return run.bold() ? bold : regular;
    }

    public static float width(PDFont font, String text, float size) {
        try {
            return font.getStringWidth(text) / 1000f * size;
        }
        catch (IOException | IllegalArgumentException ex) {
            return text.length() * size * 0.5f;
        }
    }
}
