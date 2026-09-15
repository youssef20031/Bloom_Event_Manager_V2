package com.bloom.eventmanager.ai.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record EmbedBatchRequest(@NotEmpty @Size(max = 500) List<@NotBlank String> texts) {
}
