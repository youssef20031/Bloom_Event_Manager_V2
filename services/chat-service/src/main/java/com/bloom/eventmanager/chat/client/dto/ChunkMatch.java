package com.bloom.eventmanager.chat.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChunkMatch(String id, String text, String source, int page, double score) {
}
