package com.bloom.eventmanager.storage.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DriveFileMetadata(String source, String modifiedTime) {
}
