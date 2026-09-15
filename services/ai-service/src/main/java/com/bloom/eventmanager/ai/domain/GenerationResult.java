package com.bloom.eventmanager.ai.domain;

/** {@code thoughts} is null when the model emitted no thought parts. */
public record GenerationResult(String text, String thoughts) {
}
