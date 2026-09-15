package com.bloom.eventmanager.events;

import java.time.Instant;

/** Published by knowledge-service once every batch for a document is stored. */
public record DocumentIndexed(
        String source,
        String driveFileId,
        int chunkCount,
        Instant indexedAt) {
}
