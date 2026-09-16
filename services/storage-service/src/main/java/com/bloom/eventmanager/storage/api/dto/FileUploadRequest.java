package com.bloom.eventmanager.storage.api.dto;

import jakarta.validation.constraints.NotBlank;

public record FileUploadRequest(@NotBlank String folderId, @NotBlank String fileName, @NotBlank String mimeType,
        @NotBlank String contentBase64) {
}
