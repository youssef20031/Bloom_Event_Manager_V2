package com.bloom.eventmanager.common.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonExtractorTest {

    @Test
    void extractsFromAFencedJsonBlock() {
        String text = "Here you go:\n```json\n{\"specific\": \"a\", \"broad\": \"b\"}\n```\nHope that helps.";

        assertThat(JsonExtractor.extract(text)).isEqualTo("{\"specific\": \"a\", \"broad\": \"b\"}");
    }

    @Test
    void extractsFromAFenceWithoutLanguageTag() {
        String text = "```\n[1, 2, 3]\n```";

        assertThat(JsonExtractor.extract(text)).isEqualTo("[1, 2, 3]");
    }

    @Test
    void extractsTheOutermostObjectWhenThereIsNoFence() {
        String text = "Sure! {\"ranking\": [3, 1, 2]} — let me know if you need more.";

        assertThat(JsonExtractor.extract(text)).isEqualTo("{\"ranking\": [3, 1, 2]}");
    }

    @Test
    void prefersWhicheverBracketOpensFirst() {
        String text = "[{\"a\": 1}] trailing {";

        assertThat(JsonExtractor.extract(text)).isEqualTo("[{\"a\": 1}]");
    }

    @Test
    void stripsTrailingCommasThatModelsLoveToEmit() {
        String text = "{\"items\": [1, 2, 3,], \"x\": 1,}";

        assertThat(JsonExtractor.extract(text)).isEqualTo("{\"items\": [1, 2, 3], \"x\": 1}");
    }

    @Test
    void returnsTrimmedInputWhenNothingLooksLikeJson() {
        assertThat(JsonExtractor.extract("  plain text  ")).isEqualTo("plain text");
    }

    @Test
    void toleratesNull() {
        assertThat(JsonExtractor.extract(null)).isEmpty();
    }
}
