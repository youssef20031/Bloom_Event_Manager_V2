package com.bloom.eventmanager.proposal.client.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(String role, String content, Instant timestamp, List<String> sources, String thoughts) {
}
