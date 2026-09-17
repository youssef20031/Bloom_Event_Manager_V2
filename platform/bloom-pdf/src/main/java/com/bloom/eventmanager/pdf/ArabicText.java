package com.bloom.eventmanager.pdf;

import java.util.regex.Pattern;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;

/**
 * Arabic text preparation for a left-to-right PDF content stream.
 *
 * <p>V1 reshaped with arabic-persian-reshaper and then reversed the string by
 * hand. ICU4J does both steps properly: {@link ArabicShaping} joins letters
 * into presentation forms and {@link Bidi} applies the Unicode Bidirectional
 * Algorithm, so digits and Latin fragments inside Arabic text keep their order.
 */
public final class ArabicText {

    private static final Pattern ARABIC = Pattern.compile("[\\u0600-\\u06FF]");
    private static final ArabicShaping SHAPER = new ArabicShaping(ArabicShaping.LETTERS_SHAPE);

    private ArabicText() {
    }

    public static boolean isArabic(String text) {
        return text != null && ARABIC.matcher(text).find();
    }

    /** Shaped and visually reordered; draw the result left to right with an Arabic font. */
    public static String shapeForPdf(String text) {
        if (!isArabic(text)) {
            return text;
        }
        try {
            String shaped = SHAPER.shape(text);
            Bidi bidi = new Bidi();
            bidi.setPara(shaped, Bidi.LEVEL_DEFAULT_LTR, null);
            return bidi.writeReordered(Bidi.DO_MIRRORING);
        }
        catch (ArabicShapingException ex) {
            return text;
        }
    }
}
