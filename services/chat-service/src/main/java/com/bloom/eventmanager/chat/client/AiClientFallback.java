package com.bloom.eventmanager.chat.client;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.chat.client.dto.GenerateRequest;
import com.bloom.eventmanager.chat.client.dto.GenerateResponse;
import com.bloom.eventmanager.common.error.ServiceUnavailableException;

/**
 * Runs when ai-service is down or its breaker is open. chat-service is called
 * directly by the browser, so throwing (rather than returning an empty
 * answer) surfaces as a clean 503 ProblemDetail instead of a hung request or
 * a 502 — the Event Advisor fails fast and obviously.
 */
@Component
public class AiClientFallback implements AiClient {

    @Override
    public GenerateResponse generate(GenerateRequest request) {
        throw new ServiceUnavailableException("ai-service unavailable (circuit open)", null);
    }
}
