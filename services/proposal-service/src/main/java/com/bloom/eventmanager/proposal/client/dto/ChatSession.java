package com.bloom.eventmanager.proposal.client.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatSession(String sessionId, List<ChatMessage> messages, Instant createdAt, Instant updatedAt) {
}
