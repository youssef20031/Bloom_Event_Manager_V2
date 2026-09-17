package com.bloom.eventmanager.pdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * Draws a line of {@link ScriptRuns.Run}s starting at (x, y), wrapping words
 * within {@code maxWidth}. Latin runs are drawn word by word in Helvetica at
 * {@code latinSize} — V1 used 12pt.
 *
 * <p>Arabic runs are different: a space between two Arabic (strong-RTL)
 * characters resolves to RTL under the Unicode Bidi algorithm, so a run of
 * Arabic words is a single embedded right-to-left span. Shaping and
 * reordering word by word would join letters correctly within each word but
 * leave the words themselves in logical (reading) order instead of visual
 * order — the phrase would render backwards even though each word looks
 * right. So logical words are accumulated into a group until the next one
 * would overflow {@code maxWidth}; only then is the whole group shaped and
 * bidi-reordered as one unit and drawn, in the Amiri font at
 * {@code arabicSize} — V1 used 11pt. Reordering the whole run up front and
 * then wrapping the reordered string would be equally wrong the other way:
 * it would put the logically last words first, so wrapping is decided on
 * logical (unshaped) groups, and shaping happens only once a group is final.
 *
 * <p>{@link #writeLine} and {@link #measure} both delegate to one private
 * {@link #layout} pass that decides the wrapping and records every draw
 * instruction it implies. {@code writeLine} executes those instructions;
 * {@code measure} only counts how many visual lines they span. Because
 * there is exactly one place that decides where a line breaks, the two can
 * never disagree about how tall a call's output will be — a caller that
 * needs to reserve room before drawing (to decide whether to page-break
 * first) can trust {@code measure}'s answer completely, which a hardcoded
 * per-call height estimate cannot offer once wrapping is involved: a single
 * logical line can wrap into an unbounded number of physical lines.
 */
public final class MixedTextWriter {

    private MixedTextWriter() {
    }

    public static float writeLine(PDPageContentStream content, List<ScriptRuns.Run> runs, float x, float y,
            float maxWidth, float latinSize, float arabicSize, PdfFonts fonts) throws IOException {
        float leading = leading(latinSize, arabicSize);
        float baseline = y;
        for (List<Segment> line : layout(runs, x, maxWidth, latinSize, arabicSize, fonts)) {
            for (Segment segment : line) {
                draw(content, segment.font(), segment.size(), segment.x(), baseline, segment.text());
            }
            baseline -= leading;
        }
        return baseline;
    }

    /**
     * The vertical space {@link #writeLine} will consume for this same input,
     * without touching a content stream. Callers use this to {@code ensure}
     * enough room (and page-break first if there isn't any) before drawing.
     */
    public static float measure(List<ScriptRuns.Run> runs, float x, float maxWidth, float latinSize,
            float arabicSize, PdfFonts fonts) {
        int lineCount = layout(runs, x, maxWidth, latinSize, arabicSize, fonts).size();
        return lineCount * leading(latinSize, arabicSize);
    }

    private static float leading(float latinSize, float arabicSize) {
        return Math.max(latinSize, arabicSize) * 1.4f;
    }

    /** One resolved draw instruction: font, size, absolute x, and the (already shaped, if Arabic) text. */
    private record Segment(PDFont font, float size, float x, String text) {
    }

    /**
     * Wraps {@code runs} into visual lines and records the exact sequence of
     * draw instructions each line needs, without drawing anything. This is
     * the single source of truth for where lines break; both {@link #writeLine}
     * and {@link #measure} call it so they always agree.
     */
    private static List<List<Segment>> layout(List<ScriptRuns.Run> runs, float x, float maxWidth, float latinSize,
            float arabicSize, PdfFonts fonts) {
        List<List<Segment>> lines = new ArrayList<>();
        List<Segment> current = new ArrayList<>();
        lines.add(current);
        float cursorX = x;

        for (ScriptRuns.Run run : runs) {
            PDFont font = fonts.forRun(run);
            float size = run.arabic() ? arabicSize : latinSize;

            if (run.arabic()) {
                StringBuilder pending = new StringBuilder();
                for (String word : wordsWithSpaces(run.text())) {
                    String candidate = pending + word;
                    float candidateWidth = PdfFonts.width(font, ArabicText.shapeForPdf(candidate), size);
                    boolean hasContentOnLine = cursorX > x || pending.length() > 0;
                    if (hasContentOnLine && cursorX + candidateWidth > x + maxWidth) {
                        if (pending.length() > 0) {
                            cursorX += addShaped(current, font, size, cursorX, pending.toString());
                            pending.setLength(0);
                        }
                        current = new ArrayList<>();
                        lines.add(current);
                        cursorX = x;
                        if (!word.isBlank()) {
                            pending.append(word);
                        }
                    }
                    else {
                        pending.append(word);
                    }
                }
                if (pending.length() > 0) {
                    cursorX += addShaped(current, font, size, cursorX, pending.toString());
                }
            }
            else {
                for (String word : wordsWithSpaces(run.text())) {
                    float width = PdfFonts.width(font, word, size);
                    if (cursorX > x && cursorX + width > x + maxWidth) {
                        current = new ArrayList<>();
                        lines.add(current);
                        cursorX = x;
                        if (word.isBlank()) {
                            continue;
                        }
                    }
                    current.add(new Segment(font, size, cursorX, word));
                    cursorX += width;
                }
            }
        }
        return lines;
    }

    /** Shapes and bidi-reorders one logical group as a unit, records it, and returns its drawn width. */
    private static float addShaped(List<Segment> line, PDFont font, float size, float atX, String logicalText) {
        String shaped = ArabicText.shapeForPdf(logicalText);
        line.add(new Segment(font, size, atX, shaped));
        return PdfFonts.width(font, shaped, size);
    }

    private static void draw(PDPageContentStream content, PDFont font, float size, float atX, float atY, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(atX, atY);
        content.showText(SafeText.forFont(font, text));
        content.endText();
    }

    /** Splits keeping each trailing space attached to its word so widths stay exact. */
    static List<String> wordsWithSpaces(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char c : text.toCharArray()) {
            current.append(c);
            if (c == ' ') {
                words.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            words.add(current.toString());
        }
        return words;
    }
}
