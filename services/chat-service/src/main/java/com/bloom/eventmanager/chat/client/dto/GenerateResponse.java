package com.bloom.eventmanager.chat.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerateResponse(String text, String thoughts) {
}
