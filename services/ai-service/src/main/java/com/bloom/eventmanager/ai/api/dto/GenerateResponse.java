package com.bloom.eventmanager.ai.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenerateResponse(String text, String thoughts) {
}
