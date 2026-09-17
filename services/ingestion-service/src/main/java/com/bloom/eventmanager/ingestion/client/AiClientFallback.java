package com.bloom.eventmanager.ingestion.client;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchResponse;

/**
 * Runs when ai-service is down or its breaker is open. Ingestion's work is driven by a
 * Kafka listener, so throwing here (rather than returning an empty embedding) lets the
 * exception propagate to Spring Kafka's DefaultErrorHandler, which retries and eventually
 * routes the record to the .DLT topic. Returning empty results instead would silently
 * mark a document as ingested when it was not.
 */
@Component
public class AiClientFallback implements AiClient {

    @Override
    public EmbedBatchResponse embedBatch(EmbedBatchRequest request) {
        throw new ExternalServiceException("ai-service unavailable (circuit open)", null);
    }
}
