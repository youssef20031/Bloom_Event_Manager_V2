package com.bloom.eventmanager.chat.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** V1's MessageSchema plus the {@code thoughts} field MongoChatRepository wrote. Roles: "user" | "model". */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(String role, String content, Instant timestamp, List<String> sources, String thoughts) {
}
