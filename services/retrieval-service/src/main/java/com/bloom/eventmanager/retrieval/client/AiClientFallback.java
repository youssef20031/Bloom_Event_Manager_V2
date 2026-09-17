package com.bloom.eventmanager.retrieval.client;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchResponse;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

/**
 * Runs when ai-service is down or its breaker is open. Throwing (rather than
 * returning empties) hands control to RetrievalService's existing degradation:
 * variations/re-rank fall back to the plain path, and a missing embedding is a 502.
 */
@Component
public class AiClientFallback implements AiClient {

    @Override
    public EmbedBatchResponse embedBatch(EmbedBatchRequest request) {
        throw new ExternalServiceException("ai-service unavailable (circuit open)", null);
    }

    @Override
    public GenerateResponse generate(GenerateRequest request) {
        throw new ExternalServiceException("ai-service unavailable (circuit open)", null);
    }
}
