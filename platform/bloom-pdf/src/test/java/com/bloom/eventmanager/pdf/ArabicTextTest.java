package com.bloom.eventmanager.pdf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArabicTextTest {

    @Test
    void detectsArabicScript() {
        assertThat(ArabicText.isArabic("مرحبا")).isTrue();
        assertThat(ArabicText.isArabic("Hello مرحبا")).isTrue();
        assertThat(ArabicText.isArabic("Hello")).isFalse();
        assertThat(ArabicText.isArabic("")).isFalse();
        assertThat(ArabicText.isArabic(null)).isFalse();
    }

    @Test
    void shapingJoinsLettersIntoPresentationForms() {
        String shaped = ArabicText.shapeForPdf("سلام");

        assertThat(shaped).isNotEqualTo("سلام");
        // Presentation Forms-B block: U+FE70–U+FEFF
        assertThat(shaped.chars().anyMatch(c -> c >= 0xFE70 && c <= 0xFEFF)).isTrue();
    }

    @Test
    void reorderingPlacesTheLogicallyFirstLetterAtTheVisualRight() {
        // Logical "سلام" starts with س; after visual reordering for LTR drawing it ends with the س form.
        String shaped = ArabicText.shapeForPdf("سلام");
        char last = shaped.charAt(shaped.length() - 1);

        assertThat(last).isBetween('ﺱ', 'ﺴ'); // SEEN presentation forms
    }

    @Test
    void latinTextPassesThroughUntouched() {
        assertThat(ArabicText.shapeForPdf("Hello, world 123")).isEqualTo("Hello, world 123");
    }
}
