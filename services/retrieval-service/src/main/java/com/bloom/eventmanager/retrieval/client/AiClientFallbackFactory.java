package com.bloom.eventmanager.retrieval.client;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.common.feign.FeignFallbacks;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchResponse;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

/**
 * Runs when ai-service is down or its breaker is open. Throwing (rather than
 * returning empties) hands control to RetrievalService's existing degradation:
 * variations/re-rank fall back to the plain path, and a missing embedding is a 502.
 *
 * <p>A {@code FallbackFactory} rather than a plain fallback because the invocation
 * handler routes <em>every</em> throwable here, including the {@code FeignException}
 * raised for a well-formed 4xx. {@link FeignFallbacks#failure} hands a 4xx back to the
 * caller unchanged so the peer's own HTTP semantics survive; the degradation paths
 * catch {@code RuntimeException} and so behave identically either way.
 */
@Component
public class AiClientFallbackFactory implements FallbackFactory<AiClient> {

    @Override
    public AiClient create(Throwable cause) {
        return new AiClient() {

            @Override
            public EmbedBatchResponse embedBatch(EmbedBatchRequest request) {
                throw failure(cause);
            }

            @Override
            public GenerateResponse generate(GenerateRequest request) {
                throw failure(cause);
            }
        };
    }

    private static RuntimeException failure(Throwable cause) {
        return FeignFallbacks.failure(cause,
                () -> new ExternalServiceException("ai-service unavailable (circuit open)", cause));
    }
}
