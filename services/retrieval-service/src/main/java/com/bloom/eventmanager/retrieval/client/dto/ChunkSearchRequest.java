package com.bloom.eventmanager.retrieval.client.dto;

public record ChunkSearchRequest(float[] embedding, int topK) {
}
