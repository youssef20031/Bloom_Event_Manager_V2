package com.bloom.eventmanager.retrieval.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** knowledge-service's search result, and the shape retrieval returns to its own callers. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChunkMatch(String id, String text, String source, int page, double score) {
}
