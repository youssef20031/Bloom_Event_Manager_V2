package com.bloom.eventmanager.retrieval.client;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.retrieval.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiClientFallbackTest {

    private final AiClientFallback fallback = new AiClientFallback();

    @Test
    void everyMethodThrowsSoRetrievalDegradesOrReports502() {
        assertThatThrownBy(() -> fallback.embedBatch(new EmbedBatchRequest(List.of("x"))))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("ai-service");
        assertThatThrownBy(() -> fallback.generate(new GenerateRequest("x")))
                .isInstanceOf(ExternalServiceException.class).hasMessageContaining("ai-service");
    }
}
