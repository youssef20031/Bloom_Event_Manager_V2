package com.bloom.eventmanager.knowledge.api.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

public record ChunksBySourcesRequest(@NotEmpty List<String> sources, @Min(1) @Max(500) Integer limit) {

    /**
     * {@code limit} is boxed for the same reason as ChunkSearchRequest.topK:
     * Boot 4.0.8's Jackson rejects an absent primitive record component rather
     * than defaulting it to 0.
     */
    public ChunksBySourcesRequest {
        if (limit == null || limit == 0) {
            limit = 50;
        }
    }
}
