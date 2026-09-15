package com.bloom.eventmanager.retrieval.client.dto;

/** Only the fields retrieval needs; ai-service applies model defaults for the rest. */
public record GenerateRequest(String input) {
}
