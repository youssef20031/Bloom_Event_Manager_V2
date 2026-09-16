package com.bloom.eventmanager.chat.domain;

import java.util.List;

public record ChatResult(String answer, String thoughts, String sessionId, List<String> sources) {
}
