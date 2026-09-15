package com.bloom.eventmanager.ai.api.dto;

import jakarta.validation.constraints.NotBlank;

public record EmbedRequest(@NotBlank String text) {
}
