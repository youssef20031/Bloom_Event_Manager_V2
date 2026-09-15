package com.bloom.eventmanager.knowledge.domain;

/** A chunk coming back from a query. Never carries the embedding — 12 KB nobody asked for. */
public record ChunkMatch(String id, String text, String source, int page, double score) {
}
