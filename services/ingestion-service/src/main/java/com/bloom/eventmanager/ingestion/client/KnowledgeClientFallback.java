package com.bloom.eventmanager.ingestion.client;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.ingestion.client.dto.DeleteDocumentResponse;

/**
 * Runs when knowledge-service is down or its breaker is open. Ingestion's work is driven
 * by a Kafka listener, so throwing here (rather than pretending the stale chunks were
 * deleted) lets the exception propagate to Spring Kafka's DefaultErrorHandler, which
 * retries and eventually routes the record to the .DLT topic. Returning an empty result
 * instead would silently mark a document as ingested when it was not.
 */
@Component
public class KnowledgeClientFallback implements KnowledgeClient {

    @Override
    public DeleteDocumentResponse deleteDocument(String source) {
        throw new ExternalServiceException("knowledge-service unavailable (circuit open)", null);
    }
}
