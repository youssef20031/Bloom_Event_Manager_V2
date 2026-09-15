package com.bloom.eventmanager.ingestion.domain;

public record TextChunk(String id, String text, String source, int page) {
}
