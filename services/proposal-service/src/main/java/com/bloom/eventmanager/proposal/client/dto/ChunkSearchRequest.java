package com.bloom.eventmanager.proposal.client.dto;

public record ChunkSearchRequest(float[] embedding, int topK) {
}
