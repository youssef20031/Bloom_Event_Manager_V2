package com.bloom.eventmanager.retrieval.domain;

import java.util.List;

import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;

/** {@code variations} is null on the plain path. */
public record RetrievalResult(
        List<ChunkMatch> chunks,
        String originalQuery,
        SearchVariations variations,
        int totalRetrieved,
        boolean aiEnhanced) {
}
