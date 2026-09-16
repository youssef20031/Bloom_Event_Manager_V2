package com.bloom.eventmanager.storage.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CompanyFileRequest(@NotBlank String companyName, @NotBlank String fileName, @NotBlank String mimeType,
        @NotBlank String contentBase64) {
}
