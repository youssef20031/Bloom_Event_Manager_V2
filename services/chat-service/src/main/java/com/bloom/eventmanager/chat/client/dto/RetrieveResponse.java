package com.bloom.eventmanager.chat.client.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RetrieveResponse(String status, List<ChunkMatch> chunks, Map<String, Object> metadata) {
}
