package com.bloom.eventmanager.events;

/**
 * One embedded chunk in flight between ingestion-service and knowledge-service.
 *
 * <p>{@code embedding} is a raw {@code float[]} for wire compactness. That makes
 * record equality identity-based for this component — compare embeddings
 * element-wise in tests, never with {@code equals}.
 */
public record ChunkPayload(
        String id,
        String text,
        String source,
        Integer page,
        float[] embedding) {
}
