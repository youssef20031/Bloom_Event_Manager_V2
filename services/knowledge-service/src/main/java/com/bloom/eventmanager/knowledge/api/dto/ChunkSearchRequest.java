package com.bloom.eventmanager.knowledge.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ChunkSearchRequest(@NotNull float[] embedding, @Min(1) @Max(200) Integer topK) {

    /**
     * V1's RAG_TOP_K default. {@code topK} is boxed rather than {@code int} on
     * purpose: Boot 4.0.8's Jackson enables FAIL_ON_NULL_FOR_PRIMITIVES, so an
     * absent primitive component fails the canonical constructor with
     * "Cannot map null into type int" before any default can be applied.
     */
    public ChunkSearchRequest {
        if (topK == null || topK == 0) {
            topK = 15;
        }
    }
}
