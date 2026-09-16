package com.bloom.eventmanager.ingestion.domain;

public record IngestOutcome(String source, int chunkCount, boolean ocrUsed) {
}
