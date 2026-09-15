package com.bloom.eventmanager.ai.api.dto;

import java.util.List;

public record EmbedBatchResponse(List<float[]> embeddings) {
}
