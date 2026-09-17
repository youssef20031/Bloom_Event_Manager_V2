package com.bloom.eventmanager.proposal.client;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ServiceUnavailableException;
import com.bloom.eventmanager.proposal.client.dto.EmbedRequest;
import com.bloom.eventmanager.proposal.client.dto.EmbedResponse;
import com.bloom.eventmanager.proposal.client.dto.GenerateRequest;
import com.bloom.eventmanager.proposal.client.dto.GenerateResponse;

/**
 * Runs when ai-service is down or its breaker is open. proposal-service is
 * called directly by the browser, so throwing surfaces a clean 503
 * ProblemDetail instead of leaving the request to time out or return a
 * misleading 502.
 */
@Component
public class AiClientFallback implements AiClient {

    @Override
    public EmbedResponse embed(EmbedRequest request) {
        throw new ServiceUnavailableException("ai-service unavailable (circuit open)", null);
    }

    @Override
    public GenerateResponse generate(GenerateRequest request) {
        throw new ServiceUnavailableException("ai-service unavailable (circuit open)", null);
    }
}
