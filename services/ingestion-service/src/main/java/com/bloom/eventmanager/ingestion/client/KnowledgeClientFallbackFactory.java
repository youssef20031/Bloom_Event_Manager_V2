package com.bloom.eventmanager.ingestion.client;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.common.feign.FeignFallbacks;
import com.bloom.eventmanager.ingestion.client.dto.DeleteDocumentResponse;

/**
 * Runs when knowledge-service is down or its breaker is open. Ingestion's work is driven
 * by a Kafka listener, so throwing here (rather than pretending the stale chunks were
 * deleted) lets the exception propagate to Spring Kafka's DefaultErrorHandler, which
 * retries and eventually routes the record to the -dlt dead-letter topic. Returning an empty result
 * instead would silently mark a document as ingested when it was not.
 *
 * <p>A {@code FallbackFactory} rather than a plain fallback because the invocation
 * handler routes <em>every</em> throwable here, including the {@code FeignException}
 * raised for a well-formed 4xx. {@link FeignFallbacks#failure} hands a 4xx back to the
 * caller unchanged so the peer's own HTTP semantics survive — both paths still reach the
 * error handler and the DLT, but the recorded cause names what actually happened.
 */
@Component
public class KnowledgeClientFallbackFactory implements FallbackFactory<KnowledgeClient> {

    @Override
    public KnowledgeClient create(Throwable cause) {
        return new KnowledgeClient() {

            @Override
            public DeleteDocumentResponse deleteDocument(String source) {
                throw failure(cause);
            }
        };
    }

    private static RuntimeException failure(Throwable cause) {
        return FeignFallbacks.failure(cause,
                () -> new ExternalServiceException(FeignFallbacks.unavailableMessage("knowledge-service", cause), cause));
    }
}
