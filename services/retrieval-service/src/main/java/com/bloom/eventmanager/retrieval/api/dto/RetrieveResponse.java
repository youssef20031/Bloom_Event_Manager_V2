package com.bloom.eventmanager.retrieval.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.domain.SearchVariations;

/** V1's response, field for field. */
public record RetrieveResponse(String status, List<ChunkMatch> chunks, Metadata metadata) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Metadata(
            String originalQuery,
            SearchVariations variations,
            int totalRetrieved,
            int returned,
            boolean aiEnhanced) {
    }
}
