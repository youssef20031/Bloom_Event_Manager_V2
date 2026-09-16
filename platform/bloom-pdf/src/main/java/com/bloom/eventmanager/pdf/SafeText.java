package com.bloom.eventmanager.pdf;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * Guarantees a string is safe to pass to {@code PDPageContentStream.showText}
 * for a given font.
 *
 * <p>PDFBox's Standard 14 fonts (Helvetica, Courier, ...) use WinAnsiEncoding
 * and {@code PDFont.encode} throws for any character outside it — including
 * plain control characters such as {@code \r} and {@code \n}, not just exotic
 * glyphs. V1 (PDFKit) never throws here: unsupported glyphs are silently
 * dropped or boxed. This restores that degrade-gracefully behavior instead of
 * letting one bad character abort an entire document, without adding font
 * support V1 never had (an embedded font, such as Arabic Amiri, still
 * encodes its own script's text unchanged; only characters that font itself
 * cannot draw are substituted).
 */
public final class SafeText {

    private static final char PLACEHOLDER = '?';

    private SafeText() {
    }

    /**
     * Returns {@code text} with every control character turned into a single
     * space, and every remaining character {@code font} cannot encode
     * replaced with a visible placeholder — so the result can always be
     * drawn with {@code font} without {@code showText} throwing.
     */
    public static String forFont(PDFont font, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = normalizeControlCharacters(text);
        if (encodable(font, normalized)) {
            return normalized;
        }
        StringBuilder result = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            String ch = String.valueOf(normalized.charAt(i));
            result.append(encodable(font, ch) ? ch : PLACEHOLDER);
        }
        return result.toString();
    }

    private static String normalizeControlCharacters(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            result.append(Character.isISOControl(c) ? ' ' : c);
        }
        return result.toString();
    }

    private static boolean encodable(PDFont font, String text) {
        try {
            font.encode(text);
            return true;
        }
        catch (IOException | IllegalArgumentException ex) {
            return false;
        }
    }
}
