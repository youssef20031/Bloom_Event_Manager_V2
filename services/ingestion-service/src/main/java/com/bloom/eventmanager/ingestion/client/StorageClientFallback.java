package com.bloom.eventmanager.ingestion.client;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;

/**
 * Runs when storage-service is down or its breaker is open. Ingestion's work is driven
 * by a Kafka listener, so throwing here (rather than returning empty bytes) lets the
 * exception propagate to Spring Kafka's DefaultErrorHandler, which retries and
 * eventually routes the record to the .DLT topic. Empty bytes would be indexed as a
 * document with no content.
 */
@Component
public class StorageClientFallback implements StorageClient {

    @Override
    public byte[] download(String fileId) {
        throw new ExternalServiceException("storage-service unavailable (circuit open)", null);
    }
}
