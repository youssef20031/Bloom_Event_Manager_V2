package com.bloom.eventmanager.retrieval.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * V1's request body. {@code useAI} keeps V1's capitalisation on the wire; the
 * React QueryForm sends it that way.
 */
public record RetrieveRequest(
        String query,
        float[] queryEmbedding,
        @Min(1) @Max(100) Integer topK,
        Boolean useAI) {

    public boolean hasQuery() {
        return query != null && !query.isBlank();
    }

    public boolean hasEmbedding() {
        return queryEmbedding != null && queryEmbedding.length > 0;
    }

    /** V1 treats an absent {@code useAI} as true; the default is resolved here, not downstream. */
    public boolean useAiOrDefault() {
        return useAI == null || useAI;
    }
}
