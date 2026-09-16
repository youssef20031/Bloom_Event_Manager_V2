package com.bloom.eventmanager.pdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * Draws a line of {@link ScriptRuns.Run}s starting at (x, y), wrapping words
 * within {@code maxWidth}. Arabic runs are shaped per drawn segment and set in
 * the Amiri font at {@code arabicSize}; Latin runs in Helvetica at
 * {@code latinSize} — V1 used 11pt and 12pt respectively.
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
            for (String word : wordsWithSpaces(run.text())) {
                String drawable = run.arabic() ? ArabicText.shapeForPdf(word) : word;
                float width = PdfFonts.width(font, drawable, size);
                if (cursorX > x && cursorX + width > x + maxWidth) {
                    baseline -= leading;
                    cursorX = x;
                    if (word.isBlank()) {
                        continue;
                    }
                }
                content.beginText();
                content.setFont(font, size);
                content.newLineAtOffset(cursorX, baseline);
                content.showText(drawable);
                content.endText();
                cursorX += width;
            }
        }
        return baseline - leading;
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
