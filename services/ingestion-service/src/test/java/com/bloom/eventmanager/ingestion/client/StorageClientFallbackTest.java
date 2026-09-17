package com.bloom.eventmanager.ingestion.client;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.common.error.ExternalServiceException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageClientFallbackTest {

    private final StorageClientFallback fallback = new StorageClientFallback();

    @Test
    void downloadThrowsSoTheKafkaListenerRetriesAndEventuallyDlts() {
        assertThatThrownBy(() -> fallback.download("drive-1"))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("storage-service");
    }
}
