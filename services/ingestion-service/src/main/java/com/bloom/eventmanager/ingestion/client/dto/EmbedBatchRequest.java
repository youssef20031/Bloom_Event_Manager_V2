package com.bloom.eventmanager.ingestion.client.dto;

import java.util.List;

public record EmbedBatchRequest(List<String> texts) {
}
