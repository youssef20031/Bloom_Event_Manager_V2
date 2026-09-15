package com.bloom.eventmanager.events;

import java.util.List;

/**
 * A bounded slice of one document's embedded chunks.
 *
 * <p>Batching is a hard requirement, not an optimisation: a 3072-dimension
 * {@code float[]} serialises to roughly 12 KB, so an unbounded batch would blow
 * past Kafka's 1 MB default message limit. The producer emits batches in
 * sequence and marks the final one with {@code last = true}.
 */
public record ChunkBatchReady(
        String source,
        String driveFileId,
        int seq,
        boolean last,
        List<ChunkPayload> chunks) {

    public static final int MAX_CHUNKS_PER_BATCH = 20;

    public ChunkBatchReady {
        if (chunks == null) {
            throw new IllegalArgumentException("chunks must not be null");
        }
        if (chunks.size() > MAX_CHUNKS_PER_BATCH) {
            throw new IllegalArgumentException(
                    "batch holds " + chunks.size() + " chunks; maximum is " + MAX_CHUNKS_PER_BATCH);
        }
    }
}
