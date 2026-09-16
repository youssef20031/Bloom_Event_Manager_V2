package com.bloom.eventmanager.storage.api.dto;

import jakarta.validation.constraints.NotBlank;

public record FolderRequest(@NotBlank String parentFolderId, @NotBlank String name) {
}
