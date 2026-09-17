package com.bloom.eventmanager.chat.client.dto;

public record GenerateRequest(String input, Config config) {

    public record Config(Boolean includeThoughts, Double temperature, Integer maxOutputTokens) {
    }
}
