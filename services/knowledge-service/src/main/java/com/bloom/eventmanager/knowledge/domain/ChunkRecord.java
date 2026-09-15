package com.bloom.eventmanager.knowledge.domain;

/** A chunk on its way into the graph. {@code page} may be null; it is stored as 0. */
public record ChunkRecord(String id, String text, String source, Integer page, float[] embedding) {
}
