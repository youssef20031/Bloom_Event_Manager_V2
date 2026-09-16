package com.bloom.eventmanager.proposal.client.dto;

public record GenerateRequest(String input, Config config) {

    public record Config(Boolean includeThoughts, Double temperature, Integer maxOutputTokens) {
    }
}
