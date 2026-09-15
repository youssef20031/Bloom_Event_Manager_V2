package com.bloom.eventmanager.ingestion.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbedBatchResponse(List<float[]> embeddings) {
}
