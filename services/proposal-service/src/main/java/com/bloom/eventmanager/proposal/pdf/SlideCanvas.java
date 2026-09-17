package com.bloom.eventmanager.proposal.pdf;

import java.awt.Color;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

import com.bloom.eventmanager.pdf.ArabicText;
import com.bloom.eventmanager.pdf.PdfFonts;
import com.bloom.eventmanager.pdf.SafeText;

/** One 1280x720 slide. Arabic strings are shaped and right-aligned automatically. */
final class SlideCanvas implements AutoCloseable {

    static final float W = 1280f;
    static final float H = 720f;

    private final PDPageContentStream content;
    private final PdfFonts fonts;

    SlideCanvas(PDDocument doc, PdfFonts fonts) throws IOException {
        PDPage page = new PDPage(new PDRectangle(W, H));
        doc.addPage(page);
        this.content = new PDPageContentStream(doc, page);
        this.fonts = fonts;
    }

    /** y is measured from the TOP, as in V1's PDFKit coordinates. */
    private static float baseline(float yFromTop, float size) {
        return H - yFromTop - size;
    }

    void background(Color color) throws IOException {
        rect(0, 0, W, H, color);
    }

    void rect(float x, float yFromTop, float w, float h, Color fill) throws IOException {
        content.setNonStrokingColor(fill);
        content.addRect(x, H - yFromTop - h, w, h);
        content.fill();
    }

    void rectWithAlpha(float x, float yFromTop, float w, float h, Color fill, float alpha) throws IOException {
        PDExtendedGraphicsState state = new PDExtendedGraphicsState();
        state.setNonStrokingAlphaConstant(alpha);
        content.saveGraphicsState();
        content.setGraphicsStateParameters(state);
        rect(x, yFromTop, w, h, fill);
        content.restoreGraphicsState();
    }

    void strokeRect(float x, float yFromTop, float w, float h, Color color, float lineWidth) throws IOException {
        content.setStrokingColor(color);
        content.setLineWidth(lineWidth);
        content.addRect(x, H - yFromTop - h, w, h);
        content.stroke();
    }

    void line(float x1, float y1FromTop, float x2, float y2FromTop, Color color, float lineWidth) throws IOException {
        content.setStrokingColor(color);
        content.setLineWidth(lineWidth);
        content.moveTo(x1, H - y1FromTop);
        content.lineTo(x2, H - y2FromTop);
        content.stroke();
    }

    /**
     * Draws {@code text} in a box starting at x with the given width; Arabic is
     * shaped and right-aligned.
     *
     * <p>Wrapping is decided on the LOGICAL (unshaped) text, word by word, in
     * reading order — a candidate line's width is measured against its
     * shaped form (since Arabic presentation forms differ in width from base
     * letters), but the line itself stays logical until it is finalized. Only
     * then is that one finished line shaped and bidi-reordered for drawing.
     * Shaping the whole string up front and then wrapping the already-visual
     * (reordered) string — as V1 did — puts the logically-last words on the
     * first line for any Arabic text long enough to wrap: the paragraph reads
     * bottom-to-top. See {@code MixedTextWriter} for the same fix applied to
     * mixed-script lines.
     */
    void text(String text, float x, float yFromTop, float width, float size, Weight weight, Color color, Align align) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        boolean arabic = ArabicText.isArabic(text);
        PDFont font = pick(weight, arabic);
        Align effective = arabic && align == Align.LEFT ? Align.RIGHT : align;
        float leading = size * 1.3f;
        float y = yFromTop;
        for (String line : wrap(text, font, size, width, arabic)) {
            String shaped = arabic ? ArabicText.shapeForPdf(line) : line;
            // Guards against any character (including a raw control character
            // such as an embedded newline that wrap() never split on) that
            // this font cannot encode — PDFBox throws for those instead of
            // degrading gracefully the way V1 (PDFKit) did.
            String drawable = SafeText.forFont(font, shaped);
            float lineWidth = PdfFonts.width(font, drawable, size);
            float startX = switch (effective) {
                case LEFT -> x;
                case CENTER -> x + (width - lineWidth) / 2;
                case RIGHT -> x + width - lineWidth;
            };
            content.setNonStrokingColor(color);
            content.beginText();
            content.setFont(font, size);
            content.newLineAtOffset(startX, baseline(y, size));
            content.showText(drawable);
            content.endText();
            y += leading;
        }
    }

    /**
     * Upper-cases Latin text; Arabic has no case and is left as is (V1 did
     * the same). A null title is realistic (it comes straight off the AI
     * response's JSON with no non-null enforcement) so this is guarded here,
     * once, rather than at each of the eight call sites that pass
     * {@code slide.title()} straight through.
     */
    void heading(String text, float x, float yFromTop, float width, float size, Color color, Align align) throws IOException {
        if (text == null) {
            return;
        }
        text(ArabicText.isArabic(text) ? text : text.toUpperCase(), x, yFromTop, width, size, Weight.BOLD, color, align);
    }

    private PDFont pick(Weight weight, boolean arabic) {
        if (arabic) {
            return weight == Weight.BOLD ? fonts.arabicBold() : fonts.arabic();
        }
        return switch (weight) {
            case BOLD -> fonts.bold();
            case ITALIC -> fonts.italic();
            case MONO -> fonts.mono();
            case MONO_BOLD -> fonts.monoBold();
            default -> fonts.regular();
        };
    }

    /**
     * Wraps the logical {@code text} into lines that fit {@code width},
     * measuring each candidate line's shaped width when {@code arabic} is
     * true so that presentation-form widths (which differ from base letters)
     * decide the break, while the text carried in the returned lines stays
     * logical (unshaped) — see {@link #text}.
     */
    private static List<String> wrap(String text, PDFont font, float size, float width, boolean arabic) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            String measurable = arabic ? ArabicText.shapeForPdf(candidate) : candidate;
            if (PdfFonts.width(font, measurable, size) > width && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
            else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    @Override
    public void close() throws IOException {
        content.close();
    }

    enum Weight { REGULAR, BOLD, ITALIC, MONO, MONO_BOLD }

    enum Align { LEFT, CENTER, RIGHT }
}
