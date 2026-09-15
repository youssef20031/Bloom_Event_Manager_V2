package com.bloom.eventmanager.ai.domain;

/** Per-call overrides. Any null field means "model default". */
public record GenerationOptions(Boolean includeThoughts, Double temperature, Integer maxOutputTokens) {

    public static GenerationOptions defaults() {
        return new GenerationOptions(null, null, null);
    }
}
