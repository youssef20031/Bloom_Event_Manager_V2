package com.bloom.eventmanager.knowledge.api.dto;

import jakarta.validation.constraints.NotBlank;

public record DriveFileUpsertRequest(@NotBlank String source, @NotBlank String modifiedTime) {
}
