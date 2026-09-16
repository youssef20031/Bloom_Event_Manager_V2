package com.bloom.eventmanager.storage.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatBackupRequest(@NotBlank String sessionId, @NotBlank String contentBase64) {
}
