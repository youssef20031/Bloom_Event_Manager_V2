package com.bloom.eventmanager.ingestion.client;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.common.feign.FeignFallbacks;

/**
 * Runs when storage-service is down or its breaker is open. Ingestion's work is driven
 * by a Kafka listener, so throwing here (rather than returning empty bytes) lets the
 * exception propagate to Spring Kafka's DefaultErrorHandler, which retries and
 * eventually routes the record to the .DLT topic. Empty bytes would be indexed as a
 * document with no content.
 *
 * <p>A {@code FallbackFactory} rather than a plain fallback because the invocation
 * handler routes <em>every</em> throwable here, including the {@code FeignException}
 * raised for a well-formed 4xx — a file deleted from Drive between discovery and
 * download answers 404. {@link FeignFallbacks#failure} hands that back unchanged so the
 * peer's own HTTP semantics survive and the DLT record names the real cause.
 */
@Component
public class StorageClientFallbackFactory implements FallbackFactory<StorageClient> {

    @Override
    public StorageClient create(Throwable cause) {
        return new StorageClient() {

            @Override
            public byte[] download(String fileId) {
                throw failure(cause);
            }
        };
    }

    private static RuntimeException failure(Throwable cause) {
        return FeignFallbacks.failure(cause,
                () -> new ExternalServiceException("storage-service unavailable (circuit open)", cause));
    }
}
