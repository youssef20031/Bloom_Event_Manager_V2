package com.bloom.eventmanager.chat.client;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.chat.client.dto.RetrieveRequest;
import com.bloom.eventmanager.chat.client.dto.RetrieveResponse;
import com.bloom.eventmanager.common.error.ServiceUnavailableException;

/**
 * Runs when retrieval-service is down or its breaker is open. chat-service is
 * called directly by the browser, so throwing (rather than returning empty
 * chunks) surfaces as a clean 503 ProblemDetail instead of a hung request or
 * a 502 — the Event Advisor fails fast and obviously.
 */
@Component
public class RetrievalClientFallback implements RetrievalClient {

    @Override
    public RetrieveResponse retrieve(RetrieveRequest request) {
        throw new ServiceUnavailableException("retrieval-service unavailable (circuit open)", null);
    }
}
