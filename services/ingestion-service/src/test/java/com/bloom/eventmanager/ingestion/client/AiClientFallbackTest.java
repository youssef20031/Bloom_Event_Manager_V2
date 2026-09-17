package com.bloom.eventmanager.ingestion.client;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiClientFallbackTest {

    private final AiClientFallback fallback = new AiClientFallback();

    @Test
    void embedBatchThrowsSoTheKafkaListenerRetriesAndEventuallyDlts() {
        assertThatThrownBy(() -> fallback.embedBatch(new EmbedBatchRequest(List.of("x"))))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("ai-service");
    }
}
