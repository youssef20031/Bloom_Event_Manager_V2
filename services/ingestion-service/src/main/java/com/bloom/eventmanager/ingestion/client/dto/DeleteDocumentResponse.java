package com.bloom.eventmanager.ingestion.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeleteDocumentResponse(int deletedCount) {
}
