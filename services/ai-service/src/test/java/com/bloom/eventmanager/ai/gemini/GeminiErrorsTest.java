package com.bloom.eventmanager.ai.gemini;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiErrorsTest {

    @Test
    void readsA429FromTheMessage() {
        RuntimeException ex = new RuntimeException("429 RESOURCE_EXHAUSTED: quota exceeded for GenerateRequestsPerDay");

        assertThat(GeminiErrors.statusCode(ex)).hasValue(429);
        assertThat(GeminiErrors.shouldTryNextModel(ex)).isTrue();
    }

    @Test
    void readsA404BuriedInACause() {
        RuntimeException ex = new IllegalStateException("wrapped",
                new RuntimeException("404 NOT_FOUND: models/gemini-9 is not found"));

        assertThat(GeminiErrors.statusCode(ex)).hasValue(404);
        assertThat(GeminiErrors.shouldTryNextModel(ex)).isTrue();
    }

    @Test
    void aFiveHundredIsNotAReasonToSwitchModels() {
        RuntimeException ex = new RuntimeException("500 INTERNAL: backend error");

        assertThat(GeminiErrors.statusCode(ex)).hasValue(500);
        assertThat(GeminiErrors.shouldTryNextModel(ex)).isFalse();
    }

    @Test
    void noCodeMeansNoSwitch() {
        assertThat(GeminiErrors.statusCode(new RuntimeException("connection reset"))).isEmpty();
        assertThat(GeminiErrors.shouldTryNextModel(new RuntimeException("connection reset"))).isFalse();
    }

    @Test
    void doesNotMistakeA429InsideALongerNumber() {
        assertThat(GeminiErrors.statusCode(new RuntimeException("request id 14290 failed"))).isEmpty();
    }
}
