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
 * @return the baseline y after the last drawn line
 */
public final class MixedTextWriter {

    private MixedTextWriter() {
    }

    public static float writeLine(PDPageContentStream content, List<ScriptRuns.Run> runs, float x, float y,
            float maxWidth, float latinSize, float arabicSize, PdfFonts fonts) throws IOException {
        float leading = Math.max(latinSize, arabicSize) * 1.4f;
        float cursorX = x;
        float baseline = y;

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
                            cursorX += drawShaped(content, font, size, cursorX, baseline, pending.toString());
                            pending.setLength(0);
                        }
                        baseline -= leading;
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
                    cursorX += drawShaped(content, font, size, cursorX, baseline, pending.toString());
                }
            }
            else {
                for (String word : wordsWithSpaces(run.text())) {
                    float width = PdfFonts.width(font, word, size);
                    if (cursorX > x && cursorX + width > x + maxWidth) {
                        baseline -= leading;
                        cursorX = x;
                        if (word.isBlank()) {
                            continue;
                        }
                    }
                    draw(content, font, size, cursorX, baseline, word);
                    cursorX += width;
                }
            }
        }
        return baseline - leading;
    }

    /** Shapes and bidi-reorders one logical group as a unit, draws it, and returns its width. */
    private static float drawShaped(PDPageContentStream content, PDFont font, float size, float atX, float atY,
            String logicalText) throws IOException {
        String shaped = ArabicText.shapeForPdf(logicalText);
        draw(content, font, size, atX, atY, shaped);
        return PdfFonts.width(font, shaped, size);
    }

    private static void draw(PDPageContentStream content, PDFont font, float size, float atX, float atY, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(atX, atY);
        content.showText(text);
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
