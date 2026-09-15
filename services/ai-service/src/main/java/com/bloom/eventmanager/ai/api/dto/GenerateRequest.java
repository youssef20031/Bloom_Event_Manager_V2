package com.bloom.eventmanager.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

import com.bloom.eventmanager.ai.domain.GenerationOptions;

/** {@code config} matches the spec's field name; null means model defaults. */
public record GenerateRequest(@NotBlank String input, GenerationOptions config) {
}
